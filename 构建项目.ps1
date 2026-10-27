param([ValidateSet('assembleRelease', 'testDebugUnitTest', 'connectedDebugAndroidTest')][string]$Task = 'assembleRelease')
$ErrorActionPreference = 'Stop'
$OutputEncoding = [Console]::OutputEncoding = [System.Text.UTF8Encoding]::new()
$root = (Resolve-Path -LiteralPath $PSScriptRoot).Path
$alias = Join-Path ([System.IO.Path]::GetPathRoot($root)) 'XMediaBuild'
$created = $false
if (Test-Path -LiteralPath $alias) {
    $item = Get-Item -LiteralPath $alias -Force
    if ($item.LinkType -ne 'Junction' -or ([System.IO.Path]::GetFullPath([string]$item.Target).TrimEnd('\') -ne [System.IO.Path]::GetFullPath($root).TrimEnd('\'))) {
        throw "临时构建路径已被其他文件使用：$alias。为保护现有文件，脚本已停止。"
    }
} else {
    New-Item -ItemType Junction -Path $alias -Target $root | Out-Null
    $created = $true
}
try {
    $projectJdk = Join-Path $alias '_devtools\jdk21.0.13_0'
    $projectSdk = Join-Path $alias '_devtools\android-sdk'
    if (Test-Path (Join-Path $projectJdk 'bin\java.exe')) { $env:JAVA_HOME = $projectJdk }
    if (Test-Path (Join-Path $projectSdk 'platforms\android-37.2\android.jar')) { $env:ANDROID_HOME = $projectSdk }
    if (-not $env:JAVA_HOME -or -not (Test-Path (Join-Path $env:JAVA_HOME 'bin\java.exe'))) { throw '未找到 JDK 21，请设置 JAVA_HOME。' }
    if (-not $env:ANDROID_HOME -or -not (Test-Path -LiteralPath $env:ANDROID_HOME)) { throw '未找到 Android SDK，请设置 ANDROID_HOME。' }
    if ($Task -eq 'connectedDebugAndroidTest') {
        $adb = Join-Path $env:ANDROID_HOME 'platform-tools\adb.exe'
        if (-not (Test-Path -LiteralPath $adb)) { throw '未找到 adb，无法确认设备范围。' }
        $rows = @(& $adb devices | Select-Object -Skip 1 | Where-Object { $_.Trim() -ne '' })
        if ($LASTEXITCODE -ne 0 -or $rows.Count -ne 1 -or $rows[0] -notmatch '^emulator-\d+\s+device\s*$') {
            throw '设备测试会安装测试包。为保护真机数据，请只连接一台模拟器并断开其他设备后再运行。'
        }
    }
    $env:Path = (Join-Path $env:JAVA_HOME 'bin') + ';' + $env:Path
    Push-Location $alias
    try {
        & .\gradlew.bat ":app:$Task" --console plain --no-daemon
        if ($LASTEXITCODE -ne 0) { throw "构建或测试失败，退出码 $LASTEXITCODE。" }
    } finally { Pop-Location }
} finally {
    if ($created) {
        $item = Get-Item -LiteralPath $alias -Force
        if ($item.LinkType -eq 'Junction' -and ([System.IO.Path]::GetFullPath([string]$item.Target).TrimEnd('\') -eq [System.IO.Path]::GetFullPath($root).TrimEnd('\'))) {
            Remove-Item -LiteralPath $alias
        }
    }
}
