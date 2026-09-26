$ErrorActionPreference = 'Stop'
$repoRoot = Split-Path -Parent $PSScriptRoot
$configFile = Join-Path $repoRoot 'config/application-meeting-mock.yml'
$configTemplate = "$configFile.example"

if (-not (Test-Path -LiteralPath $configFile)) {
    Copy-Item -LiteralPath $configTemplate -Destination $configFile
    throw "已创建 config/application-meeting-mock.yml。请填写 DashScope API Key 后重新运行。"
}

Set-Location -LiteralPath $repoRoot
mvn -pl haizhuo-brain-bootstrap -am spring-boot:run '-Dspring-boot.run.profiles=meeting-mock'
exit $LASTEXITCODE
