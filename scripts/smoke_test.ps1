# ZhiKeTang (Spring Boot + MySQL) end-to-end smoke test
# Usage: start the app first (run.bat or mvn spring-boot:run), then:
#   powershell -NoProfile -ExecutionPolicy Bypass -File scripts\smoke_test.ps1
# NOTE: kept ASCII-only so Windows PowerShell 5.1 parses it correctly.

$base = 'http://127.0.0.1:8080'
$script:PASS = 0
$script:FAIL = 0

# Chinese literals are built from code points: Windows PowerShell 5.1 reads
# .ps1 files as ANSI, so raw UTF-8 Chinese inside the script would be mangled.
$T_QUIZ = ([char]0x62A2).ToString() + ([char]0x7B54).ToString()   # qiang-da (quiz type)
$RED_KEY = ([char]0x7EA2).ToString()                              # hong (red risk level)

function check($name, $cond, $extra = '') {
    if ($cond) { $script:PASS++; Write-Host "  [OK]   $name" }
    else { $script:FAIL++; Write-Host "  [FAIL] $name $extra" }
}

function GetProp($obj, $name) {
    if ($null -eq $obj) { return $null }
    $p = $obj.psobject.Properties[$name]
    if ($null -eq $p) { return $null }
    return $p.Value
}

function Req($method, $url, $body, $sess) {
    try {
        $params = @{ Uri = $url; Method = $method; UseBasicParsing = $true; TimeoutSec = 25 }
        if ($null -ne $body) {
            $params['Body'] = ($body | ConvertTo-Json -Depth 8)
            $params['ContentType'] = 'application/json;charset=utf-8'
        }
        if ($null -ne $sess) { $params['WebSession'] = $sess }
        $r = Invoke-WebRequest @params
        $j = $null
        try { $j = $r.Content | ConvertFrom-Json } catch { }
        return @{ status = [int]$r.StatusCode; json = $j; text = $r.Content }
    }
    catch {
        $resp = $_.Exception.Response
        $code = 0
        if ($resp) { $code = [int]$resp.StatusCode.value__ }
        return @{ status = $code; json = $null; text = $_.Exception.Message }
    }
}

Write-Host ('=' * 66)
Write-Host '  ZhiKeTang (Spring Boot + MySQL) smoke test'
Write-Host ('=' * 66)

# ---------------------------------------------------------------- 1 auth guard
Write-Host ''
Write-Host '[1] Unauthenticated access guard'
$r = Req 'GET' "$base/api/analytics/course/1/overview" $null $null
check 'API without login returns 401' ($r.status -eq 401) "status=$($r.status)"
$r = Req 'GET' "$base/teacher/dashboard" $null $null
check 'Page without login redirects to /login' ($r.status -eq 200 -and $r.text -match 'login') "status=$($r.status)"
$r = Req 'GET' "$base/login" $null $null
check 'Login page reachable' ($r.status -eq 200) "status=$($r.status)"

# ---------------------------------------------------------------- 2 teacher
Write-Host ''
Write-Host '[2] Teacher side (t001)'
$cred = @{ username = 't001'; password = '123456' } | ConvertTo-Json
$p = @{
    Uri = "$base/api/auth/login"; Method = 'POST'; UseBasicParsing = $true
    Body = $cred; ContentType = 'application/json;charset=utf-8'; SessionVariable = 'svT'
}
$r0 = Invoke-WebRequest @p
$sessT = Get-Variable -Name svT -ValueOnly
$j0 = $r0.Content | ConvertFrom-Json
check 'Teacher login ok' ($j0.code -eq 0) $r0.Content
check 'Role is teacher' ($j0.data.role -eq 'teacher')

$r = Req 'POST' "$base/api/auth/login" @{ username = 't001'; password = 'bad' } $null
check 'Wrong password rejected' ($r.json -and $r.json.code -ne 0)

foreach ($path in @('/teacher/dashboard', '/teacher/lessons', '/teacher/interactions',
                    '/teacher/assignments', '/teacher/analytics', '/teacher/students')) {
    $r = Req 'GET' "$base$path" $null $sessT
    check "Page $path" ($r.status -eq 200) "status=$($r.status)"
}

$r = Req 'GET' "$base/api/courses" $null $sessT
check 'My courses API' ($r.json -and $r.json.code -eq 0 -and $r.json.data.Count -ge 1)
$cid = 1
if ($r.json -and $r.json.data) { $cid = $r.json.data[0].course_id }

foreach ($ep in @('overview', 'attendance', 'interaction', 'score', 'knowledge', 'risk', 'correlation', 'classes')) {
    $r = Req 'GET' "$base/api/analytics/course/$cid/$ep" $null $sessT
    check "Analytics API $ep" ($r.json -and $r.json.code -eq 0) "status=$($r.status)"
}

$r = Req 'GET' "$base/api/analytics/course/$cid/overview" $null $sessT
if ($r.json -and $r.json.code -eq 0) {
    $o = $r.json.data
    $red = GetProp $o.risk $RED_KEY
    Write-Host "        Overview: students=$($o.stu_cnt) attendance=$($o.avg_attendance)% avgScore=$($o.avg_score) redRisk=$red"
}
$r = Req 'GET' "$base/api/analytics/course/$cid/correlation" $null $sessT
if ($r.json -and $r.json.code -eq 0) {
    Write-Host "        Correlation: r(interaction-score)=$($r.json.data.r_interact_score)  r(attendance-score)=$($r.json.data.r_att_score)"
}

# ---------------------------------------------------------------- 3 classroom
Write-Host ''
Write-Host '[3] Classroom flow (create lesson -> start -> checkin -> interaction)'
$r = Req 'POST' "$base/api/lessons" @{ course_id = $cid; title = 'SmokeTestLesson'; lesson_date = '2026-12-28' } $sessT
check 'Create lesson' ($r.json -and $r.json.code -eq 0) $r.text
$lid = 0
if ($r.json) { $lid = $r.json.data.lesson_id }

$r = Req 'POST' "$base/api/lessons/$lid/start" $null $sessT
check 'Start lesson and generate checkin code' ($r.json -and $r.json.code -eq 0) $r.text
$code = ''
if ($r.json) { $code = $r.json.data.checkin_code }
Write-Host "        Checkin code: $code"

$r = Req 'GET' "$base/api/lessons/$lid/live" $null $sessT
check 'Live dashboard API' ($r.json -and $r.json.code -eq 0 -and $null -ne $r.json.data.lesson.status)

$r = Req 'POST' "$base/api/interactions" @{
    lesson_id = $lid; type = $T_QUIZ; title = 'SmokeTestQuiz'
    options   = @('Alpha', 'Beta', 'Gamma', 'Delta'); correct_answer = 'A'
} $sessT
check 'Publish quiz interaction' ($r.json -and $r.json.code -eq 0) $r.text
$iid = 0
if ($r.json) { $iid = $r.json.data.inter_id }

# ---------------------------------------------------------------- 4 student
Write-Host ''
Write-Host '[4] Student side (20230101)'
$cred2 = @{ username = '20230101'; password = '123456' } | ConvertTo-Json
$p2 = @{
    Uri = "$base/api/auth/login"; Method = 'POST'; UseBasicParsing = $true
    Body = $cred2; ContentType = 'application/json;charset=utf-8'; SessionVariable = 'svS'
}
Invoke-WebRequest @p2 | Out-Null
$sessS = Get-Variable -Name svS -ValueOnly

foreach ($path in @('/student/courses', '/student/report')) {
    $r = Req 'GET' "$base$path" $null $sessS
    check "Page $path" ($r.status -eq 200) "status=$($r.status)"
}
$r = Req 'GET' "$base/student/lesson/$lid" $null $sessS
check 'Page /student/lesson/{id}' ($r.status -eq 200) "status=$($r.status)"

$r = Req 'POST' "$base/api/attendances" @{ lesson_id = $lid; code = '0000' } $sessS
check 'Wrong checkin code rejected' ($r.json -and $r.json.code -ne 0)
$r = Req 'POST' "$base/api/attendances" @{ lesson_id = $lid; code = $code } $sessS
check 'Correct checkin code accepted' ($r.json -and $r.json.code -eq 0) $r.text
$r = Req 'POST' "$base/api/attendances" @{ lesson_id = $lid; code = $code } $sessS
check 'Duplicate checkin rejected' ($r.json -and $r.json.code -ne 0)

$r = Req 'POST' "$base/api/interactions/$iid/respond" @{ answer = 'B' } $sessS
check 'Respond to interaction (wrong answer)' ($r.json -and $r.json.code -eq 0 -and $r.json.data.is_correct -eq 0) $r.text

$r = Req 'GET' "$base/api/analytics/student?course_id=$cid" $null $sessS
check 'Personal report API' ($r.json -and $r.json.code -eq 0 -and $r.json.data.kpi.rank -gt 0)
if ($r.json -and $r.json.code -eq 0) {
    Write-Host "        Student rank $($r.json.data.kpi.rank)/$($r.json.data.kpi.total), composite=$($r.json.data.kpi.composite_score)"
}

$r = Req 'POST' "$base/api/interactions" @{ lesson_id = $lid; type = $T_QUIZ; title = 'x' } $sessS
check 'Student cannot publish interaction (403)' ($r.status -eq 403) "status=$($r.status)"

# ---------------------------------------------------------------- 5 assignment
Write-Host ''
Write-Host '[5] Assignment flow'
$r = Req 'POST' "$base/api/assignments" @{ course_id = $cid; title = 'SmokeTestAssignment' } $sessT
check 'Teacher publishes assignment' ($r.json -and $r.json.code -eq 0) $r.text
$aid = 0
if ($r.json) { $aid = $r.json.data.assign_id }

$r = Req 'POST' "$base/api/submissions" @{ assign_id = $aid; content = 'smoke submit' } $sessS
check 'Student submits assignment' ($r.json -and $r.json.code -eq 0) $r.text
$r = Req 'POST' "$base/api/submissions" @{ assign_id = $aid; content = 'again' } $sessS
check 'Duplicate submission rejected' ($r.json -and $r.json.code -ne 0)

$r = Req 'GET' "$base/api/assignments/$aid/submissions" $null $sessT
check 'View submission list' ($r.json -and $r.json.code -eq 0) $r.text
if ($r.json -and $r.json.code -eq 0 -and $r.json.data.Count -ge 1) {
    $subId = $r.json.data[0].sub_id
    $r = Req 'PUT' "$base/api/submissions/$subId/score" @{ score = 88 } $sessT
    check 'Teacher grades submission' ($r.json -and $r.json.code -eq 0) $r.text
    $r = Req 'PUT' "$base/api/submissions/$subId/score" @{ score = 150 } $sessT
    check 'Invalid score rejected' ($r.json -and $r.json.code -ne 0)
}

# ---------------------------------------------------------------- 6 cleanup
Write-Host ''
Write-Host '[6] Cleanup smoke-test data'
Req 'POST' "$base/api/lessons/$lid/end" $null $sessT | Out-Null
$mysql = 'C:\Program Files\MySQL\MySQL Server 8.0\bin\mysql.exe'
& $mysql -u root '--password=123456' --default-character-set=utf8mb4 -e "DELETE FROM classroom.lessons WHERE lesson_id=$lid; DELETE FROM classroom.assignments WHERE assign_id=$aid;" 2>&1 | Out-Null
Write-Host '  Temporary data removed'
Write-Host ''
Write-Host ('=' * 66)
Write-Host "  Result: passed $($script:PASS), failed $($script:FAIL)"
Write-Host ('=' * 66)
