param(
    [Parameter(Mandatory = $true)]
    [string]$ReferenceFile,

    [Parameter(Mandatory = $true)]
    [string]$CandidateFile
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

function Read-SavedExport {
    param([string]$Path)

    $resolvedPath = (Resolve-Path -LiteralPath $Path).Path
    $document = Get-Content -LiteralPath $resolvedPath -Raw -Encoding utf8 | ConvertFrom-Json
    if ($null -eq $document.saved_saved_media) {
        throw 'The export does not contain saved_saved_media.'
    }

    $records = @($document.saved_saved_media)
    $urls = @(
        foreach ($item in $records) {
            $href = [string]$item.string_map_data.'Saved on'.href
            if (![string]::IsNullOrWhiteSpace($href)) {
                $href
            }
        }
    )

    [pscustomobject]@{
        RecordCount = $records.Count
        ValidUrlCount = $urls.Count
        MissingUrlCount = $records.Count - $urls.Count
        UniqueUrls = @($urls | Sort-Object -Unique)
        FileHash = (Get-FileHash -LiteralPath $resolvedPath -Algorithm SHA256).Hash
    }
}

$reference = Read-SavedExport $ReferenceFile
$candidate = Read-SavedExport $CandidateFile
$referenceSet = [Collections.Generic.HashSet[string]]::new([string[]]$reference.UniqueUrls)
$overlapCount = @($candidate.UniqueUrls | Where-Object { $referenceSet.Contains($_) }).Count
$candidateOnlyCount = $candidate.UniqueUrls.Count - $overlapCount
$referenceOnlyCount = $reference.UniqueUrls.Count - $overlapCount
$exactFileDuplicate = $reference.FileHash -eq $candidate.FileHash
$contentDuplicate = (
    $candidateOnlyCount -eq 0 -and
    $referenceOnlyCount -eq 0 -and
    $candidate.UniqueUrls.Count -eq $reference.UniqueUrls.Count
)

$decision = if ($contentDuplicate) {
    'duplicate_do_not_register'
} elseif ($overlapCount -gt 0) {
    'overlap_review_participant_identity'
} else {
    'distinct_candidate'
}

Write-Output "REFERENCE_RECORDS=$($reference.RecordCount)"
Write-Output "CANDIDATE_RECORDS=$($candidate.RecordCount)"
Write-Output "REFERENCE_UNIQUE_URLS=$($reference.UniqueUrls.Count)"
Write-Output "CANDIDATE_UNIQUE_URLS=$($candidate.UniqueUrls.Count)"
Write-Output "OVERLAP_URLS=$overlapCount"
Write-Output "CANDIDATE_ONLY_URLS=$candidateOnlyCount"
Write-Output "REFERENCE_ONLY_URLS=$referenceOnlyCount"
Write-Output "CANDIDATE_MISSING_URLS=$($candidate.MissingUrlCount)"
Write-Output "EXACT_FILE_DUPLICATE=$exactFileDuplicate"
Write-Output "CONTENT_DUPLICATE=$contentDuplicate"
Write-Output "DECISION=$decision"
