#Requires -Version 5.1
# Checks out a workshop branch and (re)starts the Docker stack.
# Usage: .\run-step-win.ps1 <branch> [--chat-only] [--fresh] [--no-checkout]
$ErrorActionPreference = 'Stop'

function Invoke-Native {
    param([string]$File, [string[]]$Arguments)
    # Windows PowerShell can turn native stderr into ErrorRecords even on success.
    $ErrorActionPreference = 'Continue'
    $PSNativeCommandUseErrorActionPreference = $false
    $lines = @(& $File @Arguments 2>&1)
    $code = $LASTEXITCODE
    [pscustomobject]@{ Code = $code; Lines = @($lines | ForEach-Object { $_.ToString() }) }
}

function Invoke-Git {
    $result = Invoke-Native -File git -Arguments $args
    if ($result.Code -ne 0) { throw "git $($args -join ' ') failed:`n$($result.Lines -join "`n")" }
    $result.Lines
}

function Invoke-Compose {
    $result = Invoke-Native -File docker -Arguments (@('compose', '--profile', $script:profile) + $args)
    if ($result.Code -ne 0) {
        $result.Lines | Select-Object -Last 30 | ForEach-Object { Write-Host $_ }
        throw "docker compose $($args -join ' ') failed"
    }
    $result.Lines
}

try {
    $branch = ''
    $chatOnly = $false
    $fresh = $false
    $checkout = $true
    foreach ($argument in $args) {
        switch -CaseSensitive ($argument) {
            '--chat-only' { $chatOnly = $true }
            '--fresh' { $fresh = $true }
            '--no-checkout' { $checkout = $false }
            { $_ -in '-h', '--help' } {
                Write-Host 'Usage: .\run-step-win.ps1 <raw|step1...step5|master> [--chat-only] [--fresh] [--no-checkout]'
                Write-Host '  --chat-only    Run only the chat services'
                Write-Host '  --fresh        Delete chat history and MCP servers; keep accounts and bookings'
                Write-Host '  --no-checkout  Run the current checkout'
                exit 0
            }
            default {
                if ($argument.StartsWith('-')) { throw "unknown option $argument" }
                $branch = $argument
            }
        }
    }

    Get-Command git, docker -ErrorAction Stop | Out-Null
    $root = Invoke-Git rev-parse --show-toplevel
    Set-Location -LiteralPath $root

    if ($checkout) {
        if (-not $branch) { throw 'branch is required (raw, step1...step5, master)' }
        $exists = Invoke-Native -File git -Arguments @('show-ref', '--verify', '--quiet', "refs/heads/$branch")
        if ($exists.Code -ne 0) {
            $candidates = @(Invoke-Git for-each-ref '--format=%(refname:short)' "refs/heads/$branch-*")
            if ($candidates.Count -ne 1) {
                $available = (Invoke-Git for-each-ref '--format=%(refname:short)' refs/heads) -join ' '
                throw "unknown branch '$branch'. Available: $available"
            }
            $branch = $candidates[0]
        }
        if ((Invoke-Git rev-parse --abbrev-ref HEAD) -cne $branch) {
            $changes = @(Invoke-Git status --porcelain --untracked-files=no)
            if ($changes.Count -gt 0) {
                throw "uncommitted changes to tracked files; commit or stash them before switching to ${branch}:`n$($changes -join "`n")"
            }
            Write-Host "==> Checking out $branch"
            Invoke-Git checkout -q $branch | Out-Null
        }
    }
    $branch = Invoke-Git rev-parse --abbrev-ref HEAD

    if (-not (Test-Path -LiteralPath .env -PathType Leaf)) {
        Copy-Item -LiteralPath .env.example -Destination .env
        Write-Host '==> Created .env from .env.example (set GEMINI_API_KEY in it for step1 and later)'
    }

    $profile = 'full'
    if ($chatOnly) {
        $profile = 'chat'
        $running = Invoke-Native -File docker -Arguments @('compose', '--profile', 'full', 'ps', '-q', 'backend', 'frontend', 'postgres')
        if ($running.Code -eq 0 -and $running.Lines.Count -gt 0) {
            Write-Host '==> Stopping the full stack (data is kept)'
            $stopped = Invoke-Native -File docker -Arguments @('compose', '--profile', 'full', 'down')
            if ($stopped.Code -ne 0) { throw "docker compose down failed:`n$($stopped.Lines -join "`n")" }
        }
    }

    if ($fresh) {
        Write-Host '==> Deleting chat-db data (chat history, MCP servers)'
        Invoke-Compose rm -sf chat-service chat-db | Out-Null
        # Resolve the project from Compose so .env overrides and custom directory names work.
        $config = (Invoke-Compose config --format json) -join "`n" | ConvertFrom-Json
        $volumes = Invoke-Native -File docker -Arguments @('volume', 'ls', '-q', '--filter', "label=com.docker.compose.project=$($config.name)", '--filter', 'label=com.docker.compose.volume=chatdata')
        if ($volumes.Code -ne 0) { throw "docker volume ls failed:`n$($volumes.Lines -join "`n")" }
        foreach ($volume in $volumes.Lines) {
            if (-not $volume.Trim()) { continue }
            $removed = Invoke-Native -File docker -Arguments @('volume', 'rm', $volume)
            if ($removed.Code -ne 0) { throw "docker volume rm failed:`n$($removed.Lines -join "`n")" }
        }
    }

    Write-Host "==> Building and starting profile '$profile' on $branch (first build can take a few minutes)"
    Invoke-Compose up -d --build | Out-Null

    $endpoints = @(
        @{ Name = 'auth-service'; Url = 'http://localhost:8081/actuator/health' }
        @{ Name = 'chat-service'; Url = 'http://localhost:8082/actuator/health' }
        @{ Name = 'chat-ui'; Url = 'http://localhost:3001/' }
    )
    if ($profile -eq 'full') {
        $endpoints += @(
            @{ Name = 'backend'; Url = 'http://localhost:8080/actuator/health' }
            @{ Name = 'frontend'; Url = 'http://localhost:3000/' }
        )
    }
    Write-Host '==> Waiting for services'
    $deadline = [DateTime]::UtcNow.AddSeconds(300)
    foreach ($endpoint in $endpoints) {
        while ($true) {
            try {
                Invoke-WebRequest -Uri $endpoint.Url -UseBasicParsing -TimeoutSec 5 -ErrorAction Stop | Out-Null
                break
            } catch {
                if ([DateTime]::UtcNow -ge $deadline) {
                    Invoke-Compose ps | ForEach-Object { Write-Host $_ }
                    Write-Host "--- last log lines of $($endpoint.Name):"
                    Invoke-Compose logs --tail 40 $endpoint.Name | ForEach-Object { Write-Host $_ }
                    throw "$($endpoint.Name) did not become ready ($($endpoint.Url))"
                }
                Start-Sleep -Seconds 3
            }
        }
    }

    $commit = Invoke-Git rev-parse --short HEAD
    Write-Host "`n==> Branch $branch ($commit), profile $profile"
    Invoke-Compose ps --format '{{.Service}}: {{.Status}}' | ForEach-Object { Write-Host "  $_" }
    Write-Host ''
    if ($profile -eq 'full') { Write-Host '  Booking UI:      http://localhost:3000' }
    Write-Host '  Standalone chat: http://localhost:3001'
    Write-Host '  Hard-refresh the browser (Ctrl+Shift+R) after switching branches.'
} catch {
    [Console]::Error.WriteLine("ERROR: $($_.Exception.Message)")
    exit 1
}
exit 0
