$ErrorActionPreference = 'Stop'

# Для небольшого опыта достаточно штатных средств Android без отдельного сборочного проекта.
$probeRoot = $PSScriptRoot
$projectRoot = Split-Path (Split-Path $probeRoot -Parent) -Parent
# Средства разработки берутся из переменных окружения или обычных путей установки.
$sdkRoot = if ($env:ANDROID_SDK_ROOT) { $env:ANDROID_SDK_ROOT } elseif ($env:ANDROID_HOME) { $env:ANDROID_HOME } elseif ($env:LOCALAPPDATA) { Join-Path $env:LOCALAPPDATA 'Android\Sdk' } else { throw 'Укажите ANDROID_SDK_ROOT или ANDROID_HOME' }
$javaRoot = if ($env:JAVA_HOME) { $env:JAVA_HOME } else { Join-Path $env:ProgramFiles 'Android\Android Studio\jbr' }
if (-not (Test-Path -LiteralPath (Join-Path $javaRoot 'bin\javac.exe'))) { throw 'Укажите JAVA_HOME с установленным JDK' }
$buildTools = Join-Path $sdkRoot 'build-tools\35.0.0'
$androidJar = Join-Path $sdkRoot 'platforms\android-35\android.jar'
$outputRoot = [System.IO.Path]::GetFullPath((Join-Path $projectRoot 'scratch\card-probe-build'))
$env:JAVA_HOME = $javaRoot

# После переноса кода старые вложенные классы не должны попадать в новый пакет.
$workspacePrefix = [System.IO.Path]::GetFullPath($projectRoot) + [System.IO.Path]::DirectorySeparatorChar
if (-not $outputRoot.StartsWith($workspacePrefix, [System.StringComparison]::OrdinalIgnoreCase)) {
    throw 'Каталог сборки находится вне проекта'
}
New-Item -ItemType Directory -Force -Path $outputRoot | Out-Null
foreach ($stageName in @('generated', 'classes', 'dex')) {
    $stagePath = [System.IO.Path]::GetFullPath((Join-Path $outputRoot $stageName))
    if ((Split-Path $stagePath -Parent) -ne $outputRoot) {
        throw 'Небезопасный путь промежуточной сборки'
    }
    if (Test-Path -LiteralPath $stagePath) {
        Remove-Item -LiteralPath $stagePath -Recurse -Force
    }
    New-Item -ItemType Directory -Path $stagePath | Out-Null
}

# Ресурсы и имена класса ресурсов создаются из того же макета, который получит системный хост.
& "$buildTools\aapt.exe" package -f -m -J "$outputRoot\generated" -M "$probeRoot\AndroidManifest.xml" -S "$probeRoot\res" -A "$projectRoot\assets\wallpapers" -A "$projectRoot\assets\kanji" -I $androidJar -F "$outputRoot\resources.apk"
if ($LASTEXITCODE -ne 0) { throw 'Не удалось собрать ресурсы' }
$sources = @(Get-ChildItem $probeRoot -Filter '*.java' | ForEach-Object FullName) + @(Get-ChildItem "$outputRoot\generated" -Recurse -Filter '*.java' | ForEach-Object FullName)
& "$javaRoot\bin\javac.exe" -encoding UTF-8 --release 8 -classpath $androidJar -d "$outputRoot\classes" @sources
if ($LASTEXITCODE -ne 0) { throw 'Не удалось собрать классы' }

# Код переводится в формат Android и добавляется к пакету ресурсов.
& "$javaRoot\bin\jar.exe" cf "$outputRoot\classes.jar" -C "$outputRoot\classes" .
if ($LASTEXITCODE -ne 0) { throw 'Не удалось упаковать классы' }
& "$buildTools\d8.bat" --min-api 26 --lib $androidJar --output "$outputRoot\dex" "$outputRoot\classes.jar"
if ($LASTEXITCODE -ne 0) { throw 'Не удалось преобразовать код Android' }
Copy-Item -LiteralPath "$outputRoot\resources.apk" -Destination "$outputRoot\unsigned.apk" -Force
Push-Location "$outputRoot\dex"
try {
    & "$buildTools\aapt.exe" add "$outputRoot\unsigned.apk" classes.dex
    if ($LASTEXITCODE -ne 0) { throw 'Не удалось добавить код к ресурсам' }
} finally {
    Pop-Location
}

# Локальный отладочный ключ создаётся один раз и не подходит для выпуска приложения.
$signingRoot = Join-Path $projectRoot '.local-signing'
$keystorePath = Join-Path $signingRoot 'debug.keystore'
if (-not (Test-Path -LiteralPath $keystorePath)) {
    New-Item -ItemType Directory -Force -Path $signingRoot | Out-Null
    & "$javaRoot\bin\keytool.exe" -genkeypair -keystore $keystorePath -storepass android `
        -alias androiddebugkey -keypass android -keyalg RSA -keysize 2048 -validity 10000 `
        -dname 'CN=Android Debug,O=Android,C=US'
    if ($LASTEXITCODE -ne 0) { throw 'Не удалось создать отладочный ключ' }
}
& "$buildTools\zipalign.exe" -f 4 "$outputRoot\unsigned.apk" "$outputRoot\aligned.apk"
if ($LASTEXITCODE -ne 0) { throw 'Не удалось выровнять пакет' }
& "$buildTools\apksigner.bat" sign --ks $keystorePath --ks-key-alias androiddebugkey --ks-pass pass:android --key-pass pass:android --out "$outputRoot\nihongo-probe.apk" "$outputRoot\aligned.apk"
if ($LASTEXITCODE -ne 0) { throw 'Не удалось подписать пакет' }
& "$buildTools\apksigner.bat" verify "$outputRoot\nihongo-probe.apk"
if ($LASTEXITCODE -ne 0) { throw 'Подпись пакета не прошла проверку' }

# Два готовых файла: версия для истории и понятное имя для пересылки на телефон.
[xml]$manifest = Get-Content -LiteralPath "$probeRoot\AndroidManifest.xml" -Raw
$version = $manifest.manifest.versionName
$distributionRoot = Join-Path $projectRoot 'output'
New-Item -ItemType Directory -Force -Path $distributionRoot | Out-Null
Copy-Item -LiteralPath "$outputRoot\nihongo-probe.apk" -Destination "$distributionRoot\nihongo-words-$version.apk" -Force
Copy-Item -LiteralPath "$outputRoot\nihongo-probe.apk" -Destination "$distributionRoot\Японские-слова.apk" -Force
Write-Output "Готов к пересылке: $distributionRoot\Японские-слова.apk"
