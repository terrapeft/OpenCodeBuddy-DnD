<#
.SYNOPSIS
    Offline build of the OpenCode Buddy "Files to Context" overlay plugin.

.DESCRIPTION
    Compiles the overlay plugin with the JBR that ships with Rider and packages it into
    a JAR with FORWARD-SLASH entry names (required for IntelliJ to read META-INF/plugin.xml
    and load class resources).

    No Gradle, no network, no JDK install needed.

.PARAMETER RiderHome
    Path to the JetBrains Rider install (contains jbr\ and lib\). Defaults to the
    2026.2 install if present.

.PARAMETER Install
    If set, copys the produced JAR into the Rider plugins dir (needs Rider exited, or the
    jar will be locked).

.PARAMETER Clean
    If set, wipes out\classes, dist and build before compiling.

.EXAMPLE
    .\build.ps1 -RiderHome "C:\Program Files\JetBrains\JetBrains Rider 2026.2.3.1"
    .\build.ps1 -RiderHome "C:\Program Files\JetBrains\JetBrains Rider 2026.2.3.1" -Install
#>
[CmdletBinding()]
param(
    [string]$RiderHome = "C:\Program Files\JetBrains\JetBrains Rider 2026.2.3.1",
    [switch]$Install,
    [switch]$Clean
)

$ErrorActionPreference = "Stop"
$ScriptDir = Split-Path -Parent $MyInvocation.MyCommand.Path

function Fail($msg) { Write-Error $msg; exit 1 }

# ----- resolve Rider / JBR -----
if (-not (Test-Path $RiderHome)) {
    Fail "Rider home not found: $RiderHome"
}
$jbr = Join-Path $RiderHome "jbr"
$javac = Join-Path $jbr "bin\javac.exe"
if (-not (Test-Path $javac)) { Fail "javac not found at $javac (Rider JBR must include a JDK, not a 'nomod' runtime)" }
$lib = Join-Path $RiderHome "lib"
if (-not (Test-Path $lib)) { Fail "lib dir not found: $lib" }

Write-Host "Rider : $RiderHome"
Write-Host "Javac : $javac"

# ----- clean -----
$out   = Join-Path $ScriptDir "out\classes"
$dist  = Join-Path $ScriptDir "out\dist"
$build = Join-Path $ScriptDir "build"
if ($Clean) {
    foreach ($p in @($out, (Join-Path $ScriptDir "out"), $dist, $build)) {
        if (Test-Path $p) { Remove-Item $p -Recurse -Force }
    }
}

# ----- compile -----
New-Item -ItemType Directory -Path $out -Force | Out-Null
$src = Get-ChildItem "$ScriptDir\src\main\java" -Recurse -Filter *.java | Select-Object -ExpandProperty FullName
if (-not $src) { Fail "No .java sources found under src\main\java" }
Write-Host ("Compiling {0} source file(s) ..." -f $src.Count)
# javac writes a harmless deprecation note to stderr; that must not trip ErrorActionPreference.
$prevEA = $ErrorActionPreference
$ErrorActionPreference = 'Continue'
& $javac -encoding UTF-8 --release 17 -cp "$lib\*" -d $out $src 2>$null
$ErrorActionPreference = $prevEA
if ($LASTEXITCODE -ne 0) {
    Write-Host "Compile errors:" -ForegroundColor Red
    & $javac -encoding UTF-8 --release 17 -cp "$lib\*" -d $out $src 2>&1 | Select-Object -First 40
    exit $LASTEXITCODE
}
$classCount = (Get-ChildItem $out -Recurse -Filter *.class).Count
Write-Host "Compiled OK ($classCount classes)."

# ----- stage resources -----
New-Item -ItemType Directory -Path "$dist\META-INF" -Force | Out-Null
Copy-Item "$ScriptDir\src\main\resources\META-INF\plugin.xml" "$dist\META-INF\plugin.xml" -Force
New-Item -ItemType Directory -Path "$dist\com" -Force | Out-Null
Copy-Item "$out\com\*" "$dist\com" -Recurse -Force
@"
Manifest-Version: 1.0
Implementation-Title: OpenCode Buddy Files to Context
Implementation-Version: 1.0.0
"@ | Set-Content -Path "$dist\META-INF\MANIFEST.MF" -Encoding utf8

# ----- package JAR with FORWARD-SLASH entry names -----
Add-Type -AssemblyName System.IO.Compression
Add-Type -AssemblyName System.IO.Compression.FileSystem
New-Item -ItemType Directory -Path $build -Force | Out-Null
$jar = Join-Path $build "opencode-buddy-files.jar"
if (Test-Path $jar) { Remove-Item $jar -Force }
$fs = [System.IO.File]::Open($jar, [System.IO.FileMode]::CreateNew)
$archive = New-Object System.IO.Compression.ZipArchive($fs, [System.IO.Compression.ZipArchiveMode]::Create)
try {
    Get-ChildItem $dist -Recurse -File | ForEach-Object {
        $rel = $_.FullName.Substring($dist.Length + 1).Replace('\','/')   # forward slashes!
        $entry = $archive.CreateEntry($rel, [System.IO.Compression.CompressionLevel]::Optimal)
        $es = $entry.Open(); $in = [System.IO.File]::OpenRead($_.FullName)
        try { $in.CopyTo($es) } finally { $in.Dispose(); $es.Dispose() }
    }
} finally { $archive.Dispose(); $fs.Dispose() }

# ----- verify -----
$z = [System.IO.Compression.ZipFile]::OpenRead($jar)
$okXml = $null -ne $z.GetEntry('META-INF/plugin.xml')
$okMain = $null -ne $z.GetEntry('com/opencodebuddy/files/HostBridge.class')
$z.Dispose()
Write-Host ("JAR: {0} ({1} bytes)" -f $jar, (Get-Item $jar).Length)
Write-Host ("  META-INF/plugin.xml (fwd-slash): {0}" -f $okXml)
Write-Host ("  HostBridge.class (fwd-slash):    {0}" -f $okMain)
if (-not ($okXml -and $okMain)) { Fail "Packaging produced an invalid JAR." }

# ----- optional install -----
if ($Install) {
    $destDir = Join-Path $env:APPDATA "JetBrains\Rider2026.2\plugins\opencode-buddy-files\lib"
    $destJar = Join-Path $destDir "opencode-buddy-files.jar"
    New-Item -ItemType Directory -Path $destDir -Force | Out-Null
    try {
        Copy-Item $jar $destJar -Force -ErrorAction Stop
        Write-Host "Installed to: $destJar"
    } catch {
        Fail ("Install failed (Rider likely running and holding the jar): " + $_.Exception.Message)
    }
}

Write-Host "Done."
