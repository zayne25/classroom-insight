# AI 批改功能端到端实测：准确率 / 响应时间 / 并发量 / 复核 / 导出
# 用法：应用已启动后执行
#   powershell -NoProfile -ExecutionPolicy Bypass -File scripts\ai_grading_test.ps1
# NOTE: ASCII-only (Windows PowerShell 5.1 reads .ps1 as ANSI)

$base = 'http://127.0.0.1:8080'
$script:PASS = 0
$script:FAIL = 0

function check($name, $cond, $extra = '') {
    if ($cond) { $script:PASS++; Write-Host "  [OK]   $name" }
    else { $script:FAIL++; Write-Host "  [FAIL] $name $extra" }
}

function Req($method, $url, $body, $sess) {
    try {
        $p = @{ Uri = $url; Method = $method; UseBasicParsing = $true; TimeoutSec = 120 }
        if ($null -ne $body) {
            $p['Body'] = ($body | ConvertTo-Json -Depth 8)
            $p['ContentType'] = 'application/json;charset=utf-8'
        }
        if ($null -ne $sess) { $p['WebSession'] = $sess }
        $r = Invoke-WebRequest @p
        $j = $null
        try { $j = $r.Content | ConvertFrom-Json } catch { }
        return @{ status = [int]$r.StatusCode; json = $j; text = $r.Content; bytes = $r.RawContentLength }
    }
    catch {
        $resp = $_.Exception.Response
        $code = 0
        if ($resp) { $code = [int]$resp.StatusCode.value__ }
        return @{ status = $code; json = $null; text = $_.Exception.Message; bytes = 0 }
    }
}

Write-Host ('=' * 70)
Write-Host '  AI Grading - end-to-end measurement'
Write-Host ('=' * 70)

# ---------------------------------------------------------------- login
Write-Host ''
Write-Host '[1] Login as teacher t001'
$cred = @{ username = 't001'; password = '123456' } | ConvertTo-Json
$p = @{
    Uri = "$base/api/auth/login"; Method = 'POST'; UseBasicParsing = $true
    Body = $cred; ContentType = 'application/json;charset=utf-8'; SessionVariable = 'sv'
}
$r0 = Invoke-WebRequest @p
$sess = Get-Variable -Name sv -ValueOnly
$j0 = $r0.Content | ConvertFrom-Json
check 'teacher login ok' ($j0.code -eq 0)

# 手动拼接 Cookie 头（GetCookieHeader 在部分版本返回空，导致并发请求 401）
$cookie = ($sess.Cookies.GetCookies($base) | ForEach-Object { "$($_.Name)=$($_.Value)" }) -join '; '
if ([string]::IsNullOrWhiteSpace($cookie)) { Write-Host '        WARN: empty cookie' }

# ---------------------------------------------------------------- list
Write-Host ''
Write-Host '[2] Assignments of first course'
$courses = Req 'GET' "$base/api/courses" $null $sess
$cid = $courses.json.data[0].course_id
$rows = (Req 'GET' "$base/api/assignments?course_id=$cid" $null $sess).json.data
check 'assignment list loaded' ($rows.Count -ge 8) "count=$($rows.Count)"
$rows | ForEach-Object { Write-Host ("        #{0}  {1}  subs={2}" -f $_.assign_id, $_.assign_type, $_.sub_cnt) }

# ---------------------------------------------------------------- grading (sequential)
Write-Host ''
Write-Host '[3] Trigger AI grading for every assignment (sequential)'
$results = @()
foreach ($a in $rows) {
    $sw = [System.Diagnostics.Stopwatch]::StartNew()
    $r = Req 'POST' "$base/api/ai/grading/assignments/$($a.assign_id)" $null $sess
    $sw.Stop()
    $d = $r.json.data
    if ($null -ne $d) {
        $results += [pscustomobject]@{
            id      = $a.assign_id
            type    = $d.assign_type
            graded  = $d.graded
            srvMs   = $d.total_cost_ms
            wallMs  = $sw.ElapsedMilliseconds
            perSub  = [math]::Round($d.total_cost_ms / $d.graded, 2)
            engine  = $d.engine
            lowConf = $d.low_confidence
        }
    }
    else { Write-Host ("        grade failed #{0}: {1}" -f $a.assign_id, $r.text) }
}
check 'all assignments graded' ($results.Count -eq $rows.Count) "$($results.Count)/$($rows.Count)"

Write-Host ''
Write-Host '        ---- per-assignment result ----'
$results | Format-Table id, type, graded, srvMs, wallMs, perSub, lowConf, engine -AutoSize | Out-String -Width 120 | Write-Host

$byType = $results | Group-Object type
Write-Host '        ---- aggregated by assignment type ----'
foreach ($g in $byType) {
    $avgPer = [math]::Round(($g.Group | Measure-Object perSub -Average).Average, 2)
    $tot = ($g.Group | Measure-Object graded -Sum).Sum
    Write-Host ("        {0,-8} assignments={1}  submissions={2}  avg ms/submission={3}" -f $g.Name, $g.Group.Count, $tot, $avgPer)
}

# ---------------------------------------------------------------- review
Write-Host ''
Write-Host '[4] Teacher review (confirm + adjust)'
$first = $rows[0].assign_id
$res = (Req 'GET' "$base/api/ai/grading/assignments/$first" $null $sess).json.data
check 'grading results queryable' ($res.Count -gt 0) "count=$($res.Count)"

$r1 = $res[0]
$r2 = $res[1]
# 4a: confirm with the same score -> status should be confirmed
$rv = Req 'PUT' "$base/api/ai/grading/$($r1.result_id)/review" @{ score = [double]$r1.ai_score; comment = 'agree' } $sess
check 'review accepted (same score)' ($rv.json -and $rv.json.code -eq 0) $rv.text
# 4b: adjust with a different score -> status should be adjusted
$newScore = [math]::Min(100, [double]$r2.ai_score + 5)
$rv2 = Req 'PUT' "$base/api/ai/grading/$($r2.result_id)/review" @{ score = $newScore; comment = 'adjusted by teacher' } $sess
check 'review accepted (adjusted score)' ($rv2.json -and $rv2.json.code -eq 0) $rv2.text
# 4c: invalid score rejected
$rv3 = Req 'PUT' "$base/api/ai/grading/$($r2.result_id)/review" @{ score = 150 } $sess
check 'invalid review score rejected' ($rv3.json -and $rv3.json.code -ne 0)
# 4d: student cannot trigger grading
$rv4 = Req 'POST' "$base/api/ai/grading/assignments/$first" $null $null
check 'anonymous grading rejected (401)' ($rv4.status -eq 401) "status=$($rv4.status)"

# ---------------------------------------------------------------- export
Write-Host ''
Write-Host '[5] Export CSV'
$ex = Req 'GET' "$base/api/ai/grading/assignments/$first/export" $null $sess
check 'csv exported' ($ex.status -eq 200 -and $ex.bytes -gt 100) "status=$($ex.status) bytes=$($ex.bytes)"
Write-Host ("        csv size = {0} bytes" -f $ex.bytes)

# ---------------------------------------------------------------- stat
Write-Host ''
Write-Host '[6] Grading statistics'
$st = (Req 'GET' "$base/api/ai/grading/assignments/$first/stat" $null $sess).json.data
if ($st) {
    Write-Host ("        engine={0}  graded={1}/{2}  ai_avg={3}  avg_conf={4}  avg_ms={5}  pending={6}  adjusted={7}  deviation={8}" -f `
            $st.engine, $st.graded_cnt, $st.sub_cnt, $st.ai_avg_score, $st.avg_confidence, `
            $st.avg_cost_ms, $st.pending_cnt, $st.adjusted_cnt, $st.avg_deviation)
}
check 'statistics available' ($null -ne $st)

# ---------------------------------------------------------------- concurrency
Write-Host ''
Write-Host '[7] Concurrency: 8 parallel grading requests (System.Net.Http)'
# 说明：Invoke-WebRequest 的 -Headers Cookie 会被其内部 Cookie 容器覆盖（实测 401），
#       因此这里改用 HttpClient + CookieContainer 做真正的并发请求。
Add-Type -AssemblyName System.Net.Http
$jsid = ($sess.Cookies.GetCookies($base) |
        Where-Object { $_.Name -eq 'JSESSIONID' } | Select-Object -First 1).Value
$handler = [System.Net.Http.HttpClientHandler]::new()
$handler.CookieContainer = [System.Net.CookieContainer]::new()
$handler.CookieContainer.Add([System.Uri]$base, [System.Net.Cookie]::new('JSESSIONID', $jsid))
$client = [System.Net.Http.HttpClient]::new($handler)
$client.Timeout = [System.TimeSpan]::FromSeconds(180)

# 单请求基线
$swOne = [System.Diagnostics.Stopwatch]::StartNew()
$one = $client.PostAsync("$base/api/ai/grading/assignments/$($rows[0].assign_id)", $null)
$one.Wait()
$swOne.Stop()
Write-Host ("        single request baseline: {0} ms (status {1})" -f $swOne.ElapsedMilliseconds, [int]$one.Result.StatusCode)

$targets = $rows | Select-Object -First 8
$swAll = [System.Diagnostics.Stopwatch]::StartNew()
$tasks = @()
foreach ($t in $targets) {
    $tasks += $client.PostAsync("$base/api/ai/grading/assignments/$($t.assign_id)", $null)
}
[System.Threading.Tasks.Task]::WaitAll($tasks) | Out-Null
$swAll.Stop()
Write-Host ("        wall clock for 8 concurrent requests: {0} ms" -f $swAll.ElapsedMilliseconds)

$okCount = 0
$sumSubs = 0
$sumSrv = 0
for ($i = 0; $i -lt $tasks.Count; $i++) {
    $resp = $tasks[$i].Result
    if ($resp.IsSuccessStatusCode) {
        $body = $resp.Content.ReadAsStringAsync().Result
        $j = $body | ConvertFrom-Json
        if ($j.code -eq 0) {
            $okCount++
            $sumSubs += [int]$j.data.graded
            $sumSrv += [int]$j.data.total_cost_ms
            Write-Host ("        type={0,-6} graded={1,-4} server={2} ms" -f $j.data.assign_type, $j.data.graded, $j.data.total_cost_ms)
        }
        else { Write-Host ("        business error: {0}" -f $j.msg) }
    }
    else { Write-Host ("        HTTP ERROR status={0}" -f [int]$resp.StatusCode) }
}
check 'all concurrent requests succeeded' ($okCount -eq 8) "$okCount/8"
if ($swAll.ElapsedMilliseconds -gt 0 -and $sumSubs -gt 0) {
    $qps = [math]::Round($sumSubs / ($swAll.ElapsedMilliseconds / 1000.0), 1)
    Write-Host ("        throughput under 8-way concurrency: {0} submissions/sec ({1} submissions in {2} ms)" -f $qps, $sumSubs, $swAll.ElapsedMilliseconds)
    Write-Host ("        server-side CPU time consumed: {0} ms (parallel efficiency {1}x)" -f $sumSrv, [math]::Round($sumSrv / $swAll.ElapsedMilliseconds, 2))
}

Write-Host ''
Write-Host ('=' * 70)
Write-Host "  Result: passed $($script:PASS), failed $($script:FAIL)"
Write-Host ('=' * 70)
