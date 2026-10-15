$ErrorActionPreference = 'Stop'
$Root = $PSScriptRoot
$localEnvFile = Join-Path $Root '.env'
if (Test-Path -LiteralPath $localEnvFile) {
    foreach ($line in Get-Content -LiteralPath $localEnvFile) {
        if ($line -match '^\s*([^#=\s]+)\s*=\s*(.*?)\s*$') {
            [Environment]::SetEnvironmentVariable($matches[1], $matches[2], 'Process')
        }
    }
}
$Tools = Join-Path $Root '.tools'
$JavaHome = $env:JAVA_HOME
$MavenHome = $null

$systemJava = Get-Command java -ErrorAction SilentlyContinue
if (-not $systemJava -and (-not $JavaHome -or -not (Test-Path (Join-Path $JavaHome 'bin\java.exe')))) {
    New-Item -ItemType Directory -Force -Path $Tools | Out-Null
    $jdkZip = Join-Path $Tools 'temurin-jdk21.zip'
    $jdkDir = Join-Path $Tools 'jdk-21'
    if (-not (Test-Path $jdkDir)) {
        Write-Host 'Downloading local Temurin JDK 21…' -ForegroundColor Cyan
        Invoke-WebRequest -Uri 'https://api.adoptium.net/v3/binary/latest/21/ga/windows/x64/jdk/hotspot/normal/eclipse' -OutFile $jdkZip
        Expand-Archive -Path $jdkZip -DestinationPath $Tools -Force
        $extracted = Get-ChildItem -Path $Tools -Directory | Where-Object { $_.Name -like 'jdk-21*' } | Select-Object -First 1
        if (-not $extracted) { throw 'JDK archive extracted, but its directory was not found.' }
        Move-Item -LiteralPath $extracted.FullName -Destination $jdkDir
        Remove-Item -LiteralPath $jdkZip -Force
    }
    $JavaHome = $jdkDir
}

if (-not $JavaHome -or -not (Test-Path (Join-Path $JavaHome 'bin\java.exe'))) {
    $javaCommand = Get-Command java -ErrorAction SilentlyContinue
    if ($javaCommand) { $JavaHome = Split-Path (Split-Path $javaCommand.Source -Parent) -Parent }
}
if (-not $JavaHome -or -not (Test-Path (Join-Path $JavaHome 'bin\java.exe'))) { throw 'Could not find a usable Java installation.' }

$mvnCommand = Get-Command mvn -ErrorAction SilentlyContinue
if (-not $mvnCommand) {
    New-Item -ItemType Directory -Force -Path $Tools | Out-Null
    $mavenVersion = '3.9.11'
    $mavenDir = Join-Path $Tools "apache-maven-$mavenVersion"
    if (-not (Test-Path $mavenDir)) {
        $mavenZip = Join-Path $Tools "apache-maven-$mavenVersion-bin.zip"
        Write-Host 'Downloading local Apache Maven…' -ForegroundColor Cyan
        Invoke-WebRequest -Uri "https://archive.apache.org/dist/maven/maven-3/$mavenVersion/binaries/apache-maven-$mavenVersion-bin.zip" -OutFile $mavenZip
        Expand-Archive -Path $mavenZip -DestinationPath $Tools -Force
        Remove-Item -LiteralPath $mavenZip -Force
    }
    $MavenHome = $mavenDir
} else { $MavenHome = Split-Path (Split-Path $mvnCommand.Source -Parent) -Parent }

$env:JAVA_HOME = $JavaHome
$env:Path = (Join-Path $JavaHome 'bin') + ';' + (Join-Path $MavenHome 'bin') + ';' + $env:Path
$Port = 8080
while ($Port -lt 8090) {
    $probe = [System.Net.Sockets.TcpListener]::new([System.Net.IPAddress]::Loopback, $Port)
    try { $probe.Start(); $probe.Stop(); break }
    catch { $Port++ }
    finally { $probe.Stop() }
}
$env:SERVER_PORT = "$Port"
Write-Host "Starting RootLens at http://localhost:$Port" -ForegroundColor Green
& (Join-Path $MavenHome 'bin\mvn.cmd') "-Dmaven.repo.local=$Tools\m2" 'spring-boot:run'
