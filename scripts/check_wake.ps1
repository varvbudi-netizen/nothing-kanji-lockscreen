$ErrorActionPreference = 'Stop'

# Исполняется настоящий обработчик циклов экрана, без эмулятора и подключённых устройств.
$projectRoot = Split-Path $PSScriptRoot -Parent
# Проверка использует установленный JDK или среду Android Studio.
$javaRoot = if ($env:JAVA_HOME) { $env:JAVA_HOME } else { Join-Path $env:ProgramFiles 'Android\Android Studio\jbr' }
$javaBin = Join-Path $javaRoot 'bin'
if (-not (Test-Path -LiteralPath (Join-Path $javaBin 'javac.exe'))) { throw 'Укажите JAVA_HOME с установленным JDK' }
$testOutput = Join-Path $projectRoot 'scratch\wake-tests'
New-Item -ItemType Directory -Force -Path $testOutput | Out-Null

& "$javaBin\javac.exe" -encoding UTF-8 --release 8 -d $testOutput `
    "$projectRoot\tools\card-probe\ScreenWakeGate.java" `
    "$projectRoot\tools\card-probe\tests\ScreenWakeGateTest.java"
if ($LASTEXITCODE -ne 0) {
    throw 'Не удалось собрать проверку циклов экрана'
}

# Любая потерянная или лишняя смена завершает проверку ошибкой.
& "$javaBin\java.exe" '-Dfile.encoding=UTF-8' '-Dsun.stdout.encoding=UTF-8' `
    -cp $testOutput ru.local.nihongo.probe.ScreenWakeGateTest
if ($LASTEXITCODE -ne 0) {
    throw 'Нарушен сценарий смены при включении экрана'
}
