[CmdletBinding()]
param(
    [string]$JavaHome = 'C:/Program Files/Java/jdk-17',
    [string]$NodeModules = $env:NODE_PATH,
    [string]$GradleUserHome,
    [switch]$Offline
)

# Deterministic rehearsal: real application/DB/browser, fixed external responses.
# Never starts bootRun or touches the application's data/tossinvest database.
$ErrorActionPreference = 'Stop'
$repoRoot = Split-Path -Parent $PSScriptRoot
$environmentNames = @('JAVA_HOME', 'NODE_PATH', 'RUN_WEB_SMOKE', 'RUN_CODEX_LIVE',
    'RUN_EXPLANATION_LIVE', 'RUN_STRATEGY_BATCH_LIVE', 'RUN_STRATEGY_EXPANSION_LIVE',
    'RUN_STRATEGY_PORTFOLIO_LIVE', 'SPRING_PROFILES_ACTIVE', 'TOSS_OAUTH_CLIENT_ID',
    'TOSS_OAUTH_CLIENT_SECRET', 'TOSS_API_BASE_URL', 'STRATEGY_PAPER_SCHEDULING_ENABLED')
$previousEnvironment = @{}
foreach ($name in $environmentNames) {
    $previousEnvironment[$name] = [Environment]::GetEnvironmentVariable($name, 'Process')
}

function Invoke-LoggedCommand {
    param([string]$Executable, [string[]]$Arguments, [string]$Log)
    # Native stderr (e.g. JVM warnings) is logged; the exit code decides success.
    $ErrorActionPreference = 'Continue'
    & $Executable @Arguments 2>&1 | Tee-Object -FilePath $Log | Out-Host
    if ($LASTEXITCODE -ne 0) {
        throw "$Executable failed with exit code $LASTEXITCODE. See $Log"
    }
}

Push-Location -LiteralPath $repoRoot
$report = [ordered]@{
    generatedAt = [DateTime]::UtcNow.ToString('o')
    status = 'RUNNING'
    externalProviders = 'FIXED_TEST_RESPONSES'
    java = $null
    node = $null
}
try {
    New-Item -ItemType Directory -Path 'build' -Force | Out-Null
    if (-not (Test-Path -LiteralPath (Join-Path $JavaHome 'bin/java.exe'))) {
        throw 'Java 17 was not found. Supply -JavaHome with your JDK 17 directory.'
    }
    $env:JAVA_HOME = $JavaHome
    $node = @(Get-Command node -CommandType Application -ErrorAction Stop)[0].Source
    if (-not $NodeModules) {
        $bundledModules = Join-Path $env:USERPROFILE '.cache/codex-runtimes/codex-primary-runtime/dependencies/node/node_modules'
        if (Test-Path -LiteralPath (Join-Path $bundledModules 'playwright')) {
            $NodeModules = $bundledModules
        }
    }
    if ($NodeModules) { $env:NODE_PATH = $NodeModules }
    & $node -e "require('playwright'); console.log('Node ' + process.version + ', Playwright ready')"
    if ($LASTEXITCODE -ne 0) { throw 'Playwright is required. Supply -NodeModules with its node_modules directory.' }
    & $node -e "require('playwright').chromium.launch({headless:true,channel:'msedge'}).then(b=>b.close()).catch(e=>{console.error(e.message);process.exit(1)})"
    if ($LASTEXITCODE -ne 0) { throw 'The existing browser tests require Microsoft Edge (msedge).' }

    foreach ($name in $environmentNames | Where-Object { $_ -like 'RUN_*' }) {
        [Environment]::SetEnvironmentVariable($name, 'false', 'Process')
    }
    $env:RUN_WEB_SMOKE = 'true'
    # OAuth bean properties are required even when external services are mocked.
    # Do not require or load application-local.yaml for a reproducible rehearsal.
    $env:SPRING_PROFILES_ACTIVE = 'stage9-test'
    $env:TOSS_OAUTH_CLIENT_ID = 'stage9-unused'
    $env:TOSS_OAUTH_CLIENT_SECRET = 'stage9-unused'
    $env:TOSS_API_BASE_URL = 'http://127.0.0.1:1'
    $env:STRATEGY_PAPER_SCHEDULING_ENABLED = 'false'
    $gradleArguments = @('test', 'bootJar', '--console=plain', '--rerun-tasks')
    if ($Offline) { $gradleArguments += '--offline' }
    if ($GradleUserHome) { $gradleArguments += @('--gradle-user-home', $GradleUserHome) }
    Invoke-LoggedCommand -Executable (Join-Path $repoRoot 'gradlew.bat') -Arguments $gradleArguments -Log 'build/stage9-java.log'

    $suites = @(Get-ChildItem -LiteralPath 'build/test-results/test' -Filter 'TEST-*.xml' | ForEach-Object {
        ([xml](Get-Content -LiteralPath $_.FullName -Raw)).testsuite
    })
    if ($suites.Count -eq 0) { throw 'No fresh JUnit test reports were produced.' }
    $counts = [ordered]@{ total = 0; passed = 0; skipped = 0; failures = 0; errors = 0 }
    foreach ($suite in $suites) {
        $counts.total += [int]$suite.tests
        $counts.skipped += [int]$suite.skipped
        $counts.failures += [int]$suite.failures
        $counts.errors += [int]$suite.errors
    }
    $counts.passed = $counts.total - $counts.skipped - $counts.failures - $counts.errors
    $report.java = $counts
    foreach ($required in @('StrategyJourneyIntegrationTests', 'StrategyWebWorkflowTests',
            'CompositeWebWorkflowTests', 'ComparisonWebWorkflowTests', 'StrategyPaperWebWorkflowTests')) {
        $suite = @($suites | Where-Object { $_.name -like "*.$required" })
        if ($suite.Count -ne 1 -or [int]$suite[0].tests -lt 1 -or [int]$suite[0].skipped -ne 0) {
            throw "Required integration suite did not run: $required"
        }
    }

    # .server.cjs needs a Java-owned fixture and must not run standalone.
    $nodeTests = @(Get-ChildItem -LiteralPath 'scripts/tests' -File |
        Where-Object { $_.Name -match '\.(test|browser)\.cjs$' } |
        Sort-Object Name | ForEach-Object { $_.FullName })
    if ($nodeTests.Count -eq 0) { throw 'No standalone Node/browser tests were found.' }
    Invoke-LoggedCommand -Executable $node -Arguments (@('--test', '--test-reporter=tap', '--test-concurrency=1') + $nodeTests) -Log 'build/stage9-node.log'
    $nodeLog = Get-Content -LiteralPath 'build/stage9-node.log' -Raw
    $nodeCounts = [ordered]@{}
    foreach ($field in @(@('total', 'tests'), @('passed', 'pass'), @('skipped', 'skipped'), @('failed', 'fail'))) {
        $match = [regex]::Match($nodeLog, "(?m)^# $($field[1]) (\d+)\s*$")
        if (-not $match.Success) { throw "Node test summary missing: $($field[1])" }
        $nodeCounts[$field[0]] = [int]$match.Groups[1].Value
    }
    $report.node = $nodeCounts
    $report.status = 'PASSED'
    Write-Host "Stage 9 passed. Java: $($counts.passed)/$($counts.total) ($($counts.skipped) skipped). Node: $($nodeCounts.passed)/$($nodeCounts.total)."
}
catch {
    $report.status = 'FAILED'
    $report.error = $_.Exception.Message
    throw
}
finally {
    $report | ConvertTo-Json -Depth 5 | Set-Content -LiteralPath 'build/stage9-verification.json' -Encoding UTF8
    foreach ($name in $environmentNames) {
        [Environment]::SetEnvironmentVariable($name, $previousEnvironment[$name], 'Process')
    }
    Pop-Location
}
