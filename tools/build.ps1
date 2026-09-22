param([string[]]$Tasks = @('testDebugUnitTest', 'assembleDebug', 'lintDebug'))
$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent $PSScriptRoot
if ($projectRoot -match '[^\x00-\x7F]') {
    $aliasPath = Join-Path $env:USERPROFILE 'jingting-workspace'
    if (-not (Test-Path -LiteralPath $aliasPath)) { New-Item -ItemType Junction -Path $aliasPath -Target $projectRoot | Out-Null }
    if ((Get-Item -LiteralPath $aliasPath).Target -ne $projectRoot) { throw 'Build alias points to another project.' }
    $projectRoot = $aliasPath
}
$env:JAVA_HOME = Join-Path $projectRoot '.tools/jdk'
$env:ANDROID_HOME = Join-Path $projectRoot '.tools/android-sdk'
$env:GRADLE_USER_HOME = Join-Path $projectRoot '.tools/gradle-home'
$env:Path = "$env:JAVA_HOME/bin;$env:ANDROID_HOME/platform-tools;$env:Path"
[System.IO.File]::WriteAllText((Join-Path $projectRoot 'local.properties'), ('sdk.dir=' + $env:ANDROID_HOME.Replace('\','/').Replace(':','\:') + "`n"))
Push-Location $projectRoot
try { & "$projectRoot/.tools/gradle/bin/gradle.bat" @Tasks --console=plain; if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE } }
finally { Pop-Location }
