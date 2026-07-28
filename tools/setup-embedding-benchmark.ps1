param(
    [switch]$Force
)

$ErrorActionPreference = "Stop"

$ProjectRoot = Split-Path -Parent $PSScriptRoot
$AssetDirectory = Join-Path $ProjectRoot "android/app/src/androidTest/assets"
$ModelPath = Join-Path $AssetDirectory "universal_sentence_encoder.tflite"
$ModelUrl = "https://storage.googleapis.com/mediapipe-models/text_embedder/universal_sentence_encoder/float32/latest/universal_sentence_encoder.tflite"
$ExpectedSha256 = "89AD3C74175DD8CAA398CC22B657296D94302D20C525C12B58B29420F7249749"

New-Item -ItemType Directory -Force -Path $AssetDirectory | Out-Null

if ($Force -or -not (Test-Path -LiteralPath $ModelPath)) {
    $TemporaryPath = "$ModelPath.download"
    try {
        Invoke-WebRequest -Uri $ModelUrl -OutFile $TemporaryPath
        Move-Item -Force -LiteralPath $TemporaryPath -Destination $ModelPath
    } finally {
        if (Test-Path -LiteralPath $TemporaryPath) {
            Remove-Item -LiteralPath $TemporaryPath
        }
    }
}

$ActualSha256 = (Get-FileHash -LiteralPath $ModelPath -Algorithm SHA256).Hash
if ($ActualSha256 -ne $ExpectedSha256) {
    throw "Model SHA-256 dogrulanamadi. Beklenen: $ExpectedSha256; bulunan: $ActualSha256"
}

$Model = Get-Item -LiteralPath $ModelPath
Write-Host "Embedding benchmark modeli hazir."
Write-Host "Dosya: $($Model.FullName)"
Write-Host "Boyut: $($Model.Length) byte"
Write-Host "SHA-256: $ActualSha256"
