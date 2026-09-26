$ErrorActionPreference = 'Stop'
$repoRoot = Split-Path -Parent $PSScriptRoot
$configFile = Join-Path $repoRoot 'config/application-meeting-mock.yml'
$configTemplate = "$configFile.example"
$localConfigFile = Join-Path $repoRoot 'config/application-local.yml'
$localConfigTemplate = "$localConfigFile.example"

if (-not (Test-Path -LiteralPath $configFile)) {
    Copy-Item -LiteralPath $configTemplate -Destination $configFile
    throw "已创建 config/application-meeting-mock.yml。请填写模型 API Key、base-url 和模型 ID 后重新运行。"
}
if (-not (Test-Path -LiteralPath $localConfigFile)) {
    Copy-Item -LiteralPath $localConfigTemplate -Destination $localConfigFile
    throw "已创建 config/application-local.yml。请填写本地 MySQL 连接信息后重新运行。"
}

$modelConfig = Get-Content -Raw -LiteralPath $configFile
if ($modelConfig -notmatch '(?m)^\s*api-key:\s*\S+') {
    throw '模型 API Key 尚未配置，请编辑 config/application-meeting-mock.yml。'
}
if ($modelConfig -notmatch '(?m)^\s*base-url:\s*https?://\S+') {
    throw '模型 base-url 尚未配置，请编辑 config/application-meeting-mock.yml。'
}
if ($modelConfig -match 'primary-model:\s*model-id-from-v1-models') {
    throw '模型 ID 尚未配置，请编辑 config/application-meeting-mock.yml。'
}

Set-Location -LiteralPath $repoRoot
$java = if ($env:JAVA_HOME) { Join-Path $env:JAVA_HOME 'bin/java.exe' } else { (Get-Command java -ErrorAction Stop).Source }
if (-not (Test-Path -LiteralPath $java)) { throw '未找到 Java 17，请检查 JDK 安装与 JAVA_HOME 配置。' }
$javaVersion = (& $java -version 2>&1 | Out-String)
if ($javaVersion -notmatch 'version "17\.') { throw '本项目需要 JDK 17。请将 JAVA_HOME 指向 JDK 17 后重新运行。' }

$maven = Get-Command mvn -ErrorAction Stop
$mavenArguments = '-ntp -pl haizhuo-brain-bootstrap -am -DskipTests package'
if ($maven.Source -match '\.(cmd|bat)$') {
    $mavenCommand = 'call "{0}" {1}' -f $maven.Source, $mavenArguments
    & $env:ComSpec /d /c $mavenCommand
} else {
    & $maven.Source -ntp -pl haizhuo-brain-bootstrap -am -DskipTests package
}
if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }

$jarFile = Join-Path $repoRoot 'haizhuo-brain-bootstrap/target/haizhuo-brain-bootstrap-0.1.0-SNAPSHOT.jar'
if (-not (Test-Path -LiteralPath $jarFile)) { throw '会议室闭环应用包不存在，Maven 打包未生成可运行文件。' }
& $java -jar $jarFile '--spring.profiles.active=local,meeting-mock'
exit $LASTEXITCODE
