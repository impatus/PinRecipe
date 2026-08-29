$ErrorActionPreference = "Stop"

$Root = Split-Path -Parent $MyInvocation.MyCommand.Path
$BuildDir = Join-Path $Root "build"
$ClassesDir = Join-Path $BuildDir "classes"
$DepsDir = Join-Path $BuildDir "deps"
$DistDir = Join-Path $Root "dist"
$JnaJar = Join-Path $Root "lib\jna.jar"
$OutputJar = Join-Path $DistDir "PinRecipe.jar"
$SourceIconPng = Join-Path $Root "Pin.png"
$OutputIcon = Join-Path $DistDir "PinRecipe.ico"

function Write-IcoFromPng {
    param(
        [Parameter(Mandatory = $true)][string]$SourcePng,
        [Parameter(Mandatory = $true)][string]$OutputIco
    )

    Add-Type -AssemblyName System.Drawing

    $sizes = @(16, 24, 32, 48, 64, 128, 256)
    $pngImages = New-Object 'System.Collections.Generic.List[byte[]]'
    $source = [System.Drawing.Image]::FromFile($SourcePng)
    try {
        foreach ($size in $sizes) {
            $bitmap = New-Object System.Drawing.Bitmap -ArgumentList $size, $size, ([System.Drawing.Imaging.PixelFormat]::Format32bppArgb)
            try {
                $graphics = [System.Drawing.Graphics]::FromImage($bitmap)
                try {
                    $graphics.CompositingQuality = [System.Drawing.Drawing2D.CompositingQuality]::HighQuality
                    $graphics.InterpolationMode = [System.Drawing.Drawing2D.InterpolationMode]::HighQualityBicubic
                    $graphics.SmoothingMode = [System.Drawing.Drawing2D.SmoothingMode]::HighQuality
                    $graphics.PixelOffsetMode = [System.Drawing.Drawing2D.PixelOffsetMode]::HighQuality
                    $graphics.Clear([System.Drawing.Color]::Transparent)
                    $graphics.DrawImage($source, 0, 0, $size, $size)
                } finally {
                    $graphics.Dispose()
                }

                $memory = New-Object System.IO.MemoryStream
                try {
                    $bitmap.Save($memory, [System.Drawing.Imaging.ImageFormat]::Png)
                    $pngImages.Add($memory.ToArray())
                } finally {
                    $memory.Dispose()
                }
            } finally {
                $bitmap.Dispose()
            }
        }
    } finally {
        $source.Dispose()
    }

    $stream = [System.IO.File]::Create($OutputIco)
    $writer = New-Object System.IO.BinaryWriter -ArgumentList $stream
    try {
        $writer.Write([UInt16]0)
        $writer.Write([UInt16]1)
        $writer.Write([UInt16]$pngImages.Count)

        $offset = 6 + ($pngImages.Count * 16)
        for ($i = 0; $i -lt $pngImages.Count; $i++) {
            $size = $sizes[$i]
            if ($size -eq 256) {
                $iconSize = [byte]0
            } else {
                $iconSize = [byte]$size
            }
            $bytes = $pngImages[$i]

            $writer.Write($iconSize)
            $writer.Write($iconSize)
            $writer.Write([byte]0)
            $writer.Write([byte]0)
            $writer.Write([UInt16]1)
            $writer.Write([UInt16]32)
            $writer.Write([UInt32]$bytes.Length)
            $writer.Write([UInt32]$offset)
            $offset += $bytes.Length
        }

        foreach ($bytes in $pngImages) {
            $writer.Write($bytes)
        }
    } finally {
        $writer.Dispose()
    }
}

if (-not (Test-Path $JnaJar)) {
    throw "Missing dependency: lib\jna.jar. Download JNA and save it as lib\jna.jar, then run build.ps1 again."
}

if (Test-Path $ClassesDir) {
    Remove-Item -LiteralPath $ClassesDir -Recurse -Force
}
if (Test-Path $DepsDir) {
    Remove-Item -LiteralPath $DepsDir -Recurse -Force
}

New-Item -ItemType Directory -Force -Path $ClassesDir, $DepsDir, $DistDir | Out-Null

$Sources = Get-ChildItem -Path (Join-Path $Root "src\main\java") -Recurse -Filter "*.java" | ForEach-Object { $_.FullName }
if (-not $Sources -or $Sources.Count -eq 0) {
    throw "No Java source files found."
}

& javac -encoding UTF-8 -cp $JnaJar -d $ClassesDir $Sources
if ($LASTEXITCODE -ne 0) {
    throw "javac failed with exit code $LASTEXITCODE"
}

$ResourcesDir = Join-Path $Root "src\main\resources"
if (Test-Path $ResourcesDir) {
    Copy-Item -Path (Join-Path $ResourcesDir "*") -Destination $ClassesDir -Recurse -Force
}

Push-Location $DepsDir
try {
    & jar xf $JnaJar
    if ($LASTEXITCODE -ne 0) {
        throw "Failed to extract JNA dependency."
    }
} finally {
    Pop-Location
}

$DepManifest = Join-Path $DepsDir "META-INF\MANIFEST.MF"
if (Test-Path $DepManifest) {
    Remove-Item -LiteralPath $DepManifest -Force
}

Copy-Item -Path (Join-Path $DepsDir "*") -Destination $ClassesDir -Recurse -Force

& jar cfe $OutputJar com.pinrecipe.Main -C $ClassesDir .
if ($LASTEXITCODE -ne 0) {
    throw "jar failed with exit code $LASTEXITCODE"
}

if (Test-Path $SourceIconPng) {
    Write-IcoFromPng -SourcePng $SourceIconPng -OutputIco $OutputIcon
    Write-Host "Built $OutputIcon"
}

Write-Host "Built $OutputJar"
