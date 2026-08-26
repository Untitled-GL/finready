# =============================================================================
# ENCODING: this file MUST stay UTF-8 *with BOM*.
# Windows PowerShell 5.1 reads BOM-less .ps1 as the ANSI codepage (CP949 here).
# A Korean word ending in a CP949 lead byte then swallows the following newline,
# so the next line of code silently becomes part of the comment above it.
# That is not a syntax error - it just makes statements disappear.
# =============================================================================
<#
.SYNOPSIS
    eval/demo_seed.json 의 상담 시나리오를 실제 서버에 태워 Coverage 판정을 라벨과 대조한다.

.DESCRIPTION
    세션 생성 → revision 저장 → coverage 분석을 시나리오마다 한 번씩 돌리고,
    Risk 별 coverageStatus 를 coverageGroundTruth 와 비교해 표로 낸다.

    Swagger 로 손으로 돌리면 긴 한글 본문을 세 번 붙여넣어야 하고, 그 과정에서
    본문이 한 글자라도 달라지면 provenance 결과가 달라져 측정이 오염된다.
    같은 입력을 그대로 쓰기 위한 스크립트다.

    LLM 을 실제로 호출하므로 비용이 든다(시나리오당 약 $0.03).

.EXAMPLE
    ./tools/run-coverage-eval.ps1 -Scenarios CONS_A_002,CONS_A_004,CONS_A_005

.EXAMPLE
    ./tools/run-coverage-eval.ps1 -Scenarios CONS_A_001 -Repeat 2
#>
[CmdletBinding()]
param(
    [string[]] $Scenarios = @('CONS_A_002', 'CONS_A_004', 'CONS_A_005'),
    [string]   $BaseUrl = 'http://localhost:8080',
    [string]   $CustomerId = 'CUST_A',
    [int]      $Repeat = 1,
    [switch]   $WarmUp,
    [string]   $SeedPath,
    [string]   $OutFile
)

$ErrorActionPreference = 'Stop'

if (-not $SeedPath) {
    $SeedPath = Join-Path $PSScriptRoot '..\src\test\resources\eval\demo_seed.json'
}

# ---------------------------------------------------------------- HTTP 헬퍼
# PS 5.1 의 Invoke-RestMethod 는 본문 문자열을 UTF-8 로 보내지 않는다.
# 한글 상담문이 깨지면 evidence 인용이 원문과 안 맞아 provenance 가 전부 실패한다.
function Invoke-Json {
    param(
        [string] $Method,
        [string] $Url,
        $Body
    )

    $params = @{
        Method      = $Method
        Uri         = $Url
        ContentType = 'application/json; charset=utf-8'
        UseBasicParsing = $true
    }
    if ($null -ne $Body) {
        $json = $Body | ConvertTo-Json -Depth 10 -Compress
        $params['Body'] = [System.Text.Encoding]::UTF8.GetBytes($json)
    }

    $response = Invoke-WebRequest @params
    $text = [System.Text.Encoding]::UTF8.GetString($response.RawContentStream.ToArray())
    if ([string]::IsNullOrWhiteSpace($text)) { return $null }
    return $text | ConvertFrom-Json
}

function Get-Prop {
    param($Object, [string] $Name)
    if ($null -eq $Object) { return $null }
    $prop = $Object.PSObject.Properties[$Name]
    if ($null -eq $prop) { return $null }
    return $prop.Value
}

# ---------------------------------------------------------------- 시드 로드
if (-not (Test-Path $SeedPath)) {
    throw "시드 파일을 찾을 수 없다: $SeedPath"
}
$seed = Get-Content -Raw -Encoding UTF8 -Path $SeedPath | ConvertFrom-Json

# 서버가 떠 있는지 먼저 본다 — 안 떠 있으면 첫 POST 에서 애매한 예외가 난다
try {
    $health = Invoke-Json -Method GET -Url "$BaseUrl/actuator/health"
    Write-Host "server: $BaseUrl (status=$($health.status))" -ForegroundColor DarkGray
}
catch {
    throw "서버에 붙지 못했다: $BaseUrl — 앱을 먼저 띄울 것. ($($_.Exception.Message))"
}

$transcript = New-Object System.Collections.Generic.List[object]
$totalMatch = 0
$totalRisk = 0

foreach ($scenarioId in $Scenarios) {

    $consultation = $seed.consultations | Where-Object { $_.id -eq $scenarioId }
    if ($null -eq $consultation) {
        Write-Warning "시드에 없는 시나리오: $scenarioId — 건너뛴다"
        continue
    }

    # 캐시 상태를 관찰하지 않고 통제한다. 스크립트는 cacheRead 를 볼 수 없고(서버 쪽 값),
    # 그걸 API 응답에 넣는 것은 디버깅 목적의 계약 확장이라 하지 않는다.
    if ($WarmUp) {
        Write-Host "[warmup] $scenarioId — 결과를 버리는 1회" -ForegroundColor DarkGray
        $warmSession = Invoke-Json -Method POST -Url "$BaseUrl/api/sessions" -Body @{
            productId = $seed.productId; customerId = $CustomerId
        }
        Invoke-Json -Method POST -Url "$BaseUrl/api/sessions/$($warmSession.sessionId)/revisions" -Body @{
            text = $consultation.text
        } | Out-Null
        Invoke-Json -Method POST -Url "$BaseUrl/api/sessions/$($warmSession.sessionId)/coverage" -Body @{} | Out-Null
    }

    for ($run = 1; $run -le $Repeat; $run++) {

        $runLabel = $scenarioId
        if ($Repeat -gt 1) { $runLabel = "$scenarioId (run $run/$Repeat)" }

        Write-Host ''
        Write-Host ('=' * 78) -ForegroundColor DarkGray
        Write-Host "$runLabel — $($consultation.title)" -ForegroundColor Cyan
        Write-Host ('=' * 78) -ForegroundColor DarkGray

        $session = Invoke-Json -Method POST -Url "$BaseUrl/api/sessions" -Body @{
            productId  = $seed.productId
            customerId = $CustomerId
        }
        $sessionId = $session.sessionId

        $revision = Invoke-Json -Method POST -Url "$BaseUrl/api/sessions/$sessionId/revisions" -Body @{
            text = $consultation.text
        }

        $started = Get-Date
        $coverage = Invoke-Json -Method POST -Url "$BaseUrl/api/sessions/$sessionId/coverage" -Body @{}
        $wallMs = [int]((Get-Date) - $started).TotalMilliseconds

        # ------------------------------------------------------- Risk 대조
        $groundTruth = $consultation.coverageGroundTruth
        $rows = New-Object System.Collections.Generic.List[object]
        $matched = 0

        foreach ($risk in $coverage.risks) {
            $expected = Get-Prop -Object $groundTruth -Name $risk.riskId
            $actual = $risk.coverageStatus
            $ok = ($expected -eq $actual)
            if ($ok) { $matched++ }

            $policy = 'warn'
            if ($risk.coveragePolicy -eq 'GATE_REQUIRED') { $policy = 'GATE' }
            $mark = 'X'
            if ($ok) { $mark = 'O' }

            $rows.Add([pscustomobject]@{
                riskId     = $risk.riskId
                policy     = $policy
                expected   = $expected
                classifier = $risk.classifierStatus
                actual     = $actual
                semantic   = $risk.semanticRelation
                downgraded = $risk.downgraded
                match      = $mark
            })
        }

        $rows | Format-Table -AutoSize | Out-String -Width 200 | Write-Host

        # ------------------------------------------------------- Gate 대조
        $gateExpected = $consultation.expectedGateResult
        $gateOk = ($gateExpected -eq $coverage.gateStatus)
        $gateColor = 'Red'
        if ($gateOk) { $gateColor = 'Green' }
        $riskColor = 'Yellow'
        if ($matched -eq $rows.Count) { $riskColor = 'Green' }

        Write-Host ("Risk  : {0}/{1} 일치" -f $matched, $rows.Count) -ForegroundColor $riskColor
        Write-Host ("Gate  : expected={0} actual={1}" -f $gateExpected, $coverage.gateStatus) -ForegroundColor $gateColor
        Write-Host ("blocking={0}" -f ($coverage.blockingRiskIds -join ',')) -ForegroundColor DarkGray
        Write-Host ("warning ={0}" -f ($coverage.warningRiskIds -join ',')) -ForegroundColor DarkGray
        # TRD §14 의 판정 단위는 S1+S2 합계다. 두 값을 따로만 보여주면 per-stage 로 읽힌다 —
        # 실제로 그 오독으로 "classifier 10.8s 라 예산 충족"이 문서에 남은 적이 있다.
        $s1 = [int] $coverage.analysis.classifierLatencyMs
        $s2 = [int] $coverage.analysis.verifierLatencyMs
        $combined = $s1 + $s2
        $budgetColor = 'Green'
        if ($combined -gt 12000) { $budgetColor = 'Red' }
        Write-Host ("latency : S1={0}ms S2={1}ms  합계={2}ms (예산 12000)  wall={3}ms  prompt={4}" -f $s1, $s2, $combined, $wallMs, $coverage.analysis.promptVersion) -ForegroundColor $budgetColor

        $totalMatch += $matched
        $totalRisk += $rows.Count

        $transcript.Add([pscustomobject]@{
            scenario     = $scenarioId
            run          = $run
            sessionId    = $sessionId
            revisionId   = $revision.revisionId
            gateExpected = $gateExpected
            gateActual   = $coverage.gateStatus
            gateMatch    = $gateOk
            riskMatched  = $matched
            riskTotal    = $rows.Count
            rows         = $rows
            analysis     = $coverage.analysis
            classifierMs = $s1
            verifierMs   = $s2
            combinedMs   = $combined
            wallMs       = $wallMs
        })
    }
}

Write-Host ''
Write-Host ('-' * 78) -ForegroundColor DarkGray
$gateMatchCount = @($transcript | Where-Object { $_.gateMatch }).Count
Write-Host ("합계  Risk {0}/{1}   Gate {2}/{3}" -f $totalMatch, $totalRisk, $gateMatchCount, $transcript.Count) -ForegroundColor Cyan

# ------------------------------------------------------- 레이턴시 집계
#
# p95 를 찍지 않는다. n<=5 에서 p95 는 지어낸 정밀도이고, 이 저장소는 이미 n=1 을
# 과신해 잘못된 원인 진단을 한 전례가 있다. 예산은 평균이 아니라 천장이므로
# max 와 "초과 횟수"가 판정에 쓰는 값이다.
function Get-Median([int[]] $values) {
    if ($values.Count -eq 0) { return 0 }
    $sorted = $values | Sort-Object
    $mid = [int][math]::Floor($sorted.Count / 2)
    if ($sorted.Count % 2 -eq 1) { return $sorted[$mid] }
    return [int](($sorted[$mid - 1] + $sorted[$mid]) / 2)
}

Write-Host ''
Write-Host ('-' * 78) -ForegroundColor DarkGray
Write-Host '레이턴시 집계 (TRD §14: S1+S2 합계 12초 / 계약 한도 wall 30초)' -ForegroundColor Cyan
Write-Host ('{0,-14} {1,3} {2,26} {3,26} {4,6} {5,6}' -f '시나리오', 'n', 'S1 min/med/max', '합계 min/med/max', '>12s', '>30s') -ForegroundColor DarkGray

foreach ($group in ($transcript | Group-Object scenario)) {
    $s1s = @($group.Group | ForEach-Object { $_.classifierMs })
    $sums = @($group.Group | ForEach-Object { $_.combinedMs })
    $walls = @($group.Group | ForEach-Object { $_.wallMs })
    $over12 = @($sums | Where-Object { $_ -gt 12000 }).Count
    $over30 = @($walls | Where-Object { $_ -gt 30000 }).Count

    $rowColor = 'Green'
    if ($over12 -gt 0) { $rowColor = 'Yellow' }
    if ($over30 -gt 0) { $rowColor = 'Red' }

    Write-Host ('{0,-14} {1,3} {2,26} {3,26} {4,6} {5,6}' -f
        $group.Name,
        $group.Count,
        ('{0}/{1}/{2}' -f ($s1s | Measure-Object -Minimum).Minimum, (Get-Median $s1s), ($s1s | Measure-Object -Maximum).Maximum),
        ('{0}/{1}/{2}' -f ($sums | Measure-Object -Minimum).Minimum, (Get-Median $sums), ($sums | Measure-Object -Maximum).Maximum),
        $over12, $over30) -ForegroundColor $rowColor
}

$allSums = @($transcript | ForEach-Object { $_.combinedMs })
$allWalls = @($transcript | ForEach-Object { $_.wallMs })
$maxSum = ($allSums | Measure-Object -Maximum).Maximum
$maxWall = ($allWalls | Measure-Object -Maximum).Maximum
Write-Host ''
Write-Host ("판정 통계  max(S1+S2)={0}ms / 12000    max(wall)={1}ms / 30000" -f $maxSum, $maxWall) -ForegroundColor $(if ($maxSum -le 12000 -and $maxWall -le 30000) { 'Green' } else { 'Red' })

# 병렬화 후에는 분석 1회당 llm_call_log 가 (배치 수 + verifier 1) 행이다.
# 커넥션 풀이 5개뿐이라 팬아웃이 자기를 측정할 기록을 조용히 파괴할 수 있다 —
# LlmCallRecorder 가 실패를 WARN 으로 삼키므로 행 수를 직접 세는 것이 유일한 확인이다.
Write-Host ("llm_call_log 확인: select stage, count(*) from finready.llm_call_log where session_id in ({0}) group by stage;" -f (($transcript | ForEach-Object { "'" + $_.sessionId + "'" }) -join ',')) -ForegroundColor DarkGray

if ($OutFile) {
    $transcript | ConvertTo-Json -Depth 10 | Out-File -FilePath $OutFile -Encoding utf8
    Write-Host "결과 저장: $OutFile" -ForegroundColor DarkGray
}
