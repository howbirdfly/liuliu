[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)]
    [string]$MarkdownPath,
    [Parameter(Mandatory = $true)]
    [string]$OutputDocxPath
)

[Console]::OutputEncoding = [System.Text.Encoding]::UTF8

$markdown = Get-Content -LiteralPath $MarkdownPath -Encoding utf8

$word = $null
$document = $null

function Add-Paragraph {
    param(
        [object]$Doc,
        [string]$Text,
        [string]$Style = "Normal",
        [switch]$Mono
    )

    $paragraph = $Doc.Content.Paragraphs.Add()
    $paragraph.Range.Text = $Text
    try {
        $paragraph.Range.Style = $Style
    } catch {
        $paragraph.Range.Style = "Normal"
    }
    if ($Mono) {
        $paragraph.Range.Font.Name = "Consolas"
        $paragraph.Range.Font.Size = 10
    }
    $paragraph.Range.InsertParagraphAfter() | Out-Null
    return $paragraph
}

try {
    $word = New-Object -ComObject Word.Application
    $word.Visible = $false
    $document = $word.Documents.Add()

    Add-Paragraph -Doc $document -Text "Software Requirements Specification" -Style "Title" | Out-Null
    Add-Paragraph -Doc $document -Text "Project: 校园超速监控系统" -Style "Subtitle" | Out-Null
    Add-Paragraph -Doc $document -Text "Published on: 2026-06-21" -Style "Subtitle" | Out-Null
    Add-Paragraph -Doc $document -Text "Version: 1.0" -Style "Subtitle" | Out-Null
    $document.Paragraphs.Last.Range.InsertBreak(7) | Out-Null

    Add-Paragraph -Doc $document -Text "Table of Contents" -Style "Heading 1" | Out-Null
    $tocRange = $document.Paragraphs.Last.Range
    $document.TablesOfContents.Add($tocRange, $true, 1, 4) | Out-Null
    $document.Paragraphs.Last.Range.InsertBreak(7) | Out-Null

    $inCodeBlock = $false
    foreach ($line in $markdown) {
        if ($line -match '^```') {
            $inCodeBlock = -not $inCodeBlock
            continue
        }

        if ($inCodeBlock) {
            Add-Paragraph -Doc $document -Text $line -Mono | Out-Null
            continue
        }

        if ([string]::IsNullOrWhiteSpace($line)) {
            Add-Paragraph -Doc $document -Text "" | Out-Null
            continue
        }

        if ($line -match '^# (.+)$') {
            if ($Matches[1] -ne 'Software Requirements Specification') {
                Add-Paragraph -Doc $document -Text $Matches[1] -Style 'Heading 1' | Out-Null
            }
            continue
        }

        if ($line -match '^## (.+)$') {
            Add-Paragraph -Doc $document -Text $Matches[1] -Style 'Heading 1' | Out-Null
            continue
        }

        if ($line -match '^### (.+)$') {
            Add-Paragraph -Doc $document -Text $Matches[1] -Style 'Heading 2' | Out-Null
            continue
        }

        if ($line -match '^#### (.+)$') {
            Add-Paragraph -Doc $document -Text $Matches[1] -Style 'Heading 3' | Out-Null
            continue
        }

        if ($line -match '^(Project|Published on|Version): ') {
            continue
        }

        Add-Paragraph -Doc $document -Text $line | Out-Null
    }

    $document.TablesOfContents.Item(1).Update() | Out-Null
    $document.SaveAs([ref]$OutputDocxPath, [ref]16)
}
finally {
    if ($document -ne $null) {
        $document.Close() | Out-Null
    }
    if ($word -ne $null) {
        $word.Quit()
    }
}
