param(
    [Parameter(Mandatory = $true)]
    [string]$InputDirectory,

    [Parameter(Mandatory = $true)]
    [ValidatePattern('^[A-Z][0-9]{3}$')]
    [string]$ParticipantId,

    [string]$OutputDirectory
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

if ([string]::IsNullOrWhiteSpace($OutputDirectory)) {
    $OutputDirectory = Join-Path $PSScriptRoot "..\.private-data\instagram\$ParticipantId"
}

function ConvertTo-NormalizedText {
    param([AllowEmptyString()][string]$Value)

    if ([string]::IsNullOrWhiteSpace($Value)) { return '' }
    $decomposed = $Value.ToLowerInvariant().Replace([char]305, 'i').Normalize(
        [Text.NormalizationForm]::FormD
    )
    $builder = [Text.StringBuilder]::new()
    foreach ($character in $decomposed.ToCharArray()) {
        $category = [Globalization.CharUnicodeInfo]::GetUnicodeCategory($character)
        if ($category -ne [Globalization.UnicodeCategory]::NonSpacingMark) {
            [void]$builder.Append($character)
        }
    }
    return ([regex]::Replace($builder.ToString(), '[^a-z0-9]+', ' ')).Trim()
}

function Get-OrCreateSalt {
    param([string]$Path)

    if (Test-Path -LiteralPath $Path) {
        return [IO.File]::ReadAllBytes($Path)
    }

    $salt = [byte[]]::new(32)
    $generator = [Security.Cryptography.RandomNumberGenerator]::Create()
    try {
        $generator.GetBytes($salt)
    } finally {
        $generator.Dispose()
    }
    [IO.File]::WriteAllBytes($Path, $salt)
    return $salt
}

function Get-PrivateId {
    param(
        [string]$Value,
        [byte[]]$Salt
    )

    $hmac = [Security.Cryptography.HMACSHA256]::new($Salt)
    try {
        $bytes = [Text.Encoding]::UTF8.GetBytes($Value)
        $digest = $hmac.ComputeHash($bytes)
        return ([BitConverter]::ToString($digest).Replace('-', '').ToLowerInvariant()).Substring(0, 24)
    } finally {
        $hmac.Dispose()
    }
}

function Get-MediaType {
    param([string]$Href)

    if ([string]::IsNullOrWhiteSpace($Href)) { return 'unknown' }
    try {
        $path = ([Uri]$Href).AbsolutePath.ToLowerInvariant()
    } catch {
        return 'unknown'
    }
    if ($path -match '/reels?/') { return 'reel' }
    if ($path -match '/p/') { return 'post' }
    if ($path -match '/tv/') { return 'video' }
    return 'unknown'
}

function Get-MementoTopic {
    param([string]$Value)

    $text = ConvertTo-NormalizedText $Value
    $rules = [ordered]@{
        'Yapay Zeka' = '\b(ai|artificial intelligence|machine learning|chatgpt|openai|generative ai)\b'
        'Kitap' = '\b(book|books|literature|reading|poetry|poem|writer|writers|novel)\b'
        'Film' = '\b(film|films|movie|movies|cinema|actor|actors|actress|actresses)\b'
        'Dizi' = '\b(tv series|television series|streaming series|soap opera)\b'
        'Haber' = '\b(news|politics|political|current events|journalism)\b'
        'Mekan' = '\b(travel|tourism|destination|destinations|city guide|restaurant|restaurants|cafe|cafes)\b'
        'Urun' = '\b(fashion|beauty|makeup|shopping|style|clothing|shoes|cars|automotive)\b'
        'Spor' = '\b(sport|sports|football|soccer|basketball|fitness|workout|athlete|athletes)\b'
        'Oyun' = '\b(game|games|gaming|video game|esports)\b'
        'Muzik' = '\b(music|musician|musicians|song|songs|singer|singers|album|albums|concert)\b'
        'Video' = '\b(video|videos|filmmaking|animation)\b'
        'Sosyal' = '\b(meme|memes|relationships|social media|internet culture|humor|comedy)\b'
        'Tarif' = '\b(food|cooking|recipe|recipes|baking|dessert|desserts|cuisine)\b'
    }

    foreach ($entry in $rules.GetEnumerator()) {
        if ($text -match $entry.Value) { return $entry.Key }
    }
    return 'Diger'
}

$resolvedInput = (Resolve-Path -LiteralPath $InputDirectory).Path
[void][IO.Directory]::CreateDirectory($OutputDirectory)
$resolvedOutput = (Resolve-Path -LiteralPath $OutputDirectory).Path
$saltPath = Join-Path $resolvedOutput '.salt'
$salt = Get-OrCreateSalt $saltPath

$savedFile = Get-ChildItem -LiteralPath $resolvedInput -File |
    Where-Object { $_.Name -like 'saved_posts*.json' } |
    Sort-Object Name |
    Select-Object -First 1
if ($null -eq $savedFile) {
    throw 'saved_posts*.json bulunamadi.'
}

$savedDocument = Get-Content -LiteralPath $savedFile.FullName -Raw -Encoding utf8 | ConvertFrom-Json
$savedRecords = @(
    foreach ($item in $savedDocument.saved_saved_media) {
        $savedOn = $item.string_map_data.'Saved on'
        $href = [string]$savedOn.href
        if ([string]::IsNullOrWhiteSpace($href)) {
            throw 'Saved item is missing href; a stable private ID cannot be created.'
        }
        $timestamp = [long]$savedOn.timestamp
        $month = if ($timestamp -gt 0) {
            [DateTimeOffset]::FromUnixTimeSeconds($timestamp).UtcDateTime.ToString('yyyy-MM')
        } else {
            $null
        }
        [ordered]@{
            item_id = Get-PrivateId -Value $href -Salt $salt
            saved_month = $month
            media_type = Get-MediaType $href
        }
    }
)

$topicCounts = [ordered]@{}
$topicFile = Join-Path $resolvedInput 'recommended_topics.json'
$topicTotal = 0
if (Test-Path -LiteralPath $topicFile) {
    $topicDocument = Get-Content -LiteralPath $topicFile -Raw -Encoding utf8 | ConvertFrom-Json
    foreach ($item in $topicDocument.topics_your_topics) {
        $topicTotal++
        $mappedTopic = Get-MementoTopic ([string]$item.string_map_data.Name.value)
        if (!$topicCounts.Contains($mappedTopic)) { $topicCounts[$mappedTopic] = 0 }
        $topicCounts[$mappedTopic]++
    }
}

$mediaCounts = [ordered]@{}
foreach ($record in $savedRecords) {
    $mediaType = [string]$record['media_type']
    if (!$mediaCounts.Contains($mediaType)) { $mediaCounts[$mediaType] = 0 }
    $mediaCounts[$mediaType]++
}

$viewedLinkCount = 0
$viewedFile = Join-Path $resolvedInput 'posts_viewed.html'
if (Test-Path -LiteralPath $viewedFile) {
    $html = Get-Content -LiteralPath $viewedFile -Raw -Encoding utf8
    $viewedLinkCount = [regex]::Matches($html, '<a\b', 'IgnoreCase').Count
}

$months = @($savedRecords.saved_month | Where-Object { $null -ne $_ } | Sort-Object -Unique)
$result = [ordered]@{
    schema_version = 1
    participant_id = $ParticipantId
    generated_at_utc = [DateTime]::UtcNow.ToString('o')
    privacy = [ordered]@{
        protection_level = 'pseudonymized_private_research_data'
        removed_fields = @('account_or_title', 'href', 'exact_timestamp', 'raw_recommended_topic')
        item_id_method = 'salted_hmac_sha256_truncated'
        raw_export_copied = $false
    }
    summary = [ordered]@{
        saved_item_count = $savedRecords.Count
        saved_month_from = if ($months.Count -gt 0) { $months[0] } else { $null }
        saved_month_until = if ($months.Count -gt 0) { $months[-1] } else { $null }
        media_type_counts = $mediaCounts
        recommended_topic_count = $topicTotal
        mapped_topic_counts = $topicCounts
        viewed_link_count = $viewedLinkCount
    }
    saved_records = $savedRecords
}

$outputFile = Join-Path $resolvedOutput 'minimized-instagram-export.private.json'
$result | ConvertTo-Json -Depth 8 | Set-Content -LiteralPath $outputFile -Encoding utf8

Write-Output "OUTPUT=$outputFile"
Write-Output "SAVED_ITEMS=$($savedRecords.Count)"
Write-Output "RECOMMENDED_TOPICS=$topicTotal"
Write-Output "VIEWED_LINKS=$viewedLinkCount"
