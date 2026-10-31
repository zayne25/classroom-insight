# Admin module smoke test (ASCII only, to avoid PS 5.1 ANSI encoding issues)
# Usage: powershell -NoProfile -ExecutionPolicy Bypass -File scripts\admin_smoke.ps1
# Optional: -Base http://127.0.0.1:8081

param(
    [string]$Base = "http://127.0.0.1:8081"
)

# PS 5.1 reads .ps1 as ANSI, so Chinese literals are built from code points
$DISABLE = [string]([char]0x505C) + [string]([char]0x7528)   # ting-yong = disabled
$ENABLE  = [string]([char]0x6B63) + [string]([char]0x5E38)   # zheng-chang = normal

$pass = 0
$fail = 0

function Check($name, $ok, $extra) {
    if ($ok) {
        $script:pass++
        Write-Host ("[PASS] " + $name + $(if ($extra) { " | " + $extra } else { "" }))
    } else {
        $script:fail++
        Write-Host ("[FAIL] " + $name + $(if ($extra) { " | " + $extra } else { "" }))
    }
}

function Login($user, $pwd, $sessName) {
    $body = @{ username = $user; password = $pwd } | ConvertTo-Json
    return Invoke-RestMethod -Uri "$Base/api/auth/login" -Method Post `
        -Body $body -ContentType "application/json" -SessionVariable $sessName
}

function Page($path, $session) {
    try {
        $r = Invoke-WebRequest -Uri ($Base + $path) -WebSession $session `
            -MaximumRedirection 0 -UseBasicParsing
        return $r.StatusCode
    } catch {
        if ($_.Exception.Response) { return [int]$_.Exception.Response.StatusCode }
        return -1
    }
}

function GetJson($path, $session) {
    try {
        $r = Invoke-WebRequest -Uri ($Base + $path) -WebSession $session -UseBasicParsing
        return ($r.Content | ConvertFrom-Json)
    } catch {
        return $null
    }
}

function Send($method, $path, $body, $session) {
    try {
        $json = if ($null -ne $body) { $body | ConvertTo-Json } else { "{}" }
        $bytes = [System.Text.Encoding]::UTF8.GetBytes($json)
        $r = Invoke-WebRequest -Uri ($Base + $path) -Method $method -Body $bytes `
            -ContentType "application/json" -WebSession $session -UseBasicParsing
        return ($r.Content | ConvertFrom-Json)
    } catch {
        if ($_.Exception.Response) {
            $sr = New-Object System.IO.StreamReader($_.Exception.Response.GetResponseStream())
            $txt = $sr.ReadToEnd()
            try { return ($txt | ConvertFrom-Json) } catch { return @{ code = -1; msg = $txt } }
        }
        return @{ code = -1; msg = $_.Exception.Message }
    }
}

Write-Host "=== 1. login as admin ==="
$login = Login "admin" "123456" "global:adminSess"
Check "admin login" ($login.code -eq 0) ("home=" + $login.data.home)
if ($login.code -ne 0) { Write-Host "cannot continue"; exit 1 }

Write-Host "=== 2. admin pages ==="
foreach ($p in @("/admin/monitor", "/admin/users", "/admin/perms", "/admin/config", "/admin/logs", "/admin/backup")) {
    $code = Page $p $global:adminSess
    Check ("page " + $p) ($code -eq 200) ("http " + $code)
}

Write-Host "=== 3. admin apis ==="
$ov = GetJson "/api/admin/monitor/overview" $global:adminSess
Check "monitor overview" ($ov -ne $null -and $ov.code -eq 0) ("users=" + $ov.data.biz.user_cnt)

$us = GetJson "/api/admin/users?page=1&size=5" $global:adminSess
Check "user list" ($us -ne $null -and $us.code -eq 0 -and $us.data.total -gt 0) ("total=" + $us.data.total)

$pm = GetJson "/api/admin/perms/matrix" $global:adminSess
Check "perm matrix" ($pm -ne $null -and $pm.code -eq 0) ("points=" + $pm.data.total)

$cfg = GetJson "/api/admin/config" $global:adminSess
Check "config list" ($cfg -ne $null -and $cfg.code -eq 0) ("items=" + $cfg.data.total)

$lg = GetJson "/api/admin/logs?size=5" $global:adminSess
Check "log search" ($lg -ne $null -and $lg.code -eq 0) ("total=" + $lg.data.total)

Write-Host "=== 4. user CRUD ==="
$created = Send "POST" "/api/admin/users" @{ username = "smoke001"; real_name = "Smoke Test"; role = "student"; password = "123456"; class_id = 1 } $global:adminSess
Check "create user" ($created.code -eq 0) $created.msg

$dup = Send "POST" "/api/admin/users" @{ username = "smoke001"; real_name = "Dup"; role = "student"; password = "123456" } $global:adminSess
Check "duplicate username rejected" ($dup.code -ne 0) $dup.msg

$weak = Send "POST" "/api/admin/users" @{ username = "smoke002"; real_name = "Weak"; role = "student"; password = "1" } $global:adminSess
Check "weak password rejected" ($weak.code -ne 0) $weak.msg

$found = GetJson "/api/admin/users?kw=smoke001" $global:adminSess
$uid = 0
if ($found -and $found.data.rows.Count -gt 0) { $uid = $found.data.rows[0].user_id }
Check "find created user" ($uid -gt 0) ("uid=" + $uid)

if ($uid -gt 0) {
    $upd = Send "PUT" ("/api/admin/users/" + $uid) @{ real_name = "Smoke Renamed"; role = "student"; gender = ""; phone = ""; class_id = 1 } $global:adminSess
    Check "update user" ($upd.code -eq 0) $upd.msg

    $st = Send "PUT" ("/api/admin/users/" + $uid + "/status") @{ status = $DISABLE } $global:adminSess
    Check "disable user" ($st.code -eq 0) $st.msg

    $back = Send "PUT" ("/api/admin/users/" + $uid + "/status") @{ status = $ENABLE } $global:adminSess
    Check "enable user" ($back.code -eq 0) $back.msg

    $self = Send "PUT" ("/api/admin/users/" + $login.data.user_id + "/status") @{ status = $DISABLE } $global:adminSess
    Check "cannot disable self" ($self.code -ne 0) $self.msg

    $pwd = Send "PUT" ("/api/admin/users/" + $uid + "/password") @{ password = "654321" } $global:adminSess
    Check "reset password" ($pwd.code -eq 0) $pwd.msg

    $del = Send "DELETE" ("/api/admin/users/" + $uid) $null $global:adminSess
    Check "delete user" ($del.code -eq 0) $del.msg
}

Write-Host "=== 5. config update + audit ==="
$cfgUpd = Send "PUT" "/api/admin/config" @{ config_key = "risk.red_score"; config_value = "61" } $global:adminSess
Check "update config" ($cfgUpd.code -eq 0) ("detail=" + $cfgUpd.data)

$bad = Send "PUT" "/api/admin/config" @{ config_key = "risk.red_score"; config_value = "999" } $global:adminSess
Check "out-of-range config rejected" ($bad.code -ne 0) $bad.msg

$reset = Send "POST" "/api/admin/config/risk.red_score/reset" $null $global:adminSess
Check "reset config" ($reset.code -eq 0) ("detail=" + $reset.data)

$lg2 = GetJson "/api/admin/logs?module=%E7%B3%BB%E7%BB%9F%E9%85%8D%E7%BD%AE&size=5" $global:adminSess
Check "config change audited" ($lg2 -ne $null -and $lg2.data.total -ge 1) ("rows=" + $lg2.data.total)

Write-Host "=== 6. permission boundary ==="
$permBad = Send "PUT" "/api/admin/perms" @{ role = "admin"; perm_code = "user:create"; enabled = $false } $global:adminSess
Check "admin role perm locked" ($permBad.code -ne 0) $permBad.msg

$permStu = Send "PUT" "/api/admin/perms" @{ role = "student"; perm_code = "user:create"; enabled = $true } $global:adminSess
Check "student cannot gain admin perm" ($permStu.code -ne 0) $permStu.msg

Write-Host "=== 7. backup ==="
$bk = Send "POST" "/api/admin/backup/create" $null $global:adminSess
Check "create backup" ($bk.code -eq 0) ($bk.data.file_name + " " + $bk.data.size_text)
$bkList = GetJson "/api/admin/backup/list" $global:adminSess
Check "backup list" ($bkList -ne $null -and $bkList.data.Count -ge 1) ("count=" + $bkList.data.Count)

Write-Host "=== 8. access control for non-admin ==="
$login2 = Login "20230101" "123456" "global:stuSess"
Check "student login" ($login2.code -eq 0) ""
$stuApi = GetJson "/api/admin/users?page=1&size=5" $global:stuSess
Check "student api denied" ($stuApi -eq $null) ("response=" + $(if ($stuApi) { $stuApi.code } else { "403/none" }))
$stuPage = Page "/admin/users" $global:stuSess
Check "student page redirected" ($stuPage -eq 302) ("http " + $stuPage)

Write-Host ""
Write-Host ("RESULT: pass=" + $pass + " fail=" + $fail)
if ($fail -gt 0) { exit 1 } else { exit 0 }
