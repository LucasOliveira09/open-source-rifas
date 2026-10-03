param(
    [string]$EnvironmentFile = '.env'
)

$repoRoot = Split-Path -Parent $PSScriptRoot
$environmentPath = Join-Path $repoRoot $EnvironmentFile
if (-not (Test-Path -LiteralPath $environmentPath -PathType Leaf)) {
    throw "Arquivo de ambiente não encontrado: $EnvironmentFile"
}

$username = (Read-Host 'Usuário administrativo').Trim()
if ($username -notmatch '^[A-Za-z0-9._@-]{1,80}$') {
    throw 'Use um usuário de 1 a 80 caracteres: letras, números, ponto, traço, sublinhado ou @.'
}

$securePassword = Read-Host 'Senha administrativa' -AsSecureString
$passwordPointer = [IntPtr]::Zero
$passwordBytes = $null
$salt = New-Object byte[] 16
$derived = $null
try {
    $passwordPointer = [Runtime.InteropServices.Marshal]::SecureStringToBSTR($securePassword)
    $password = [Runtime.InteropServices.Marshal]::PtrToStringBSTR($passwordPointer)
    if ([string]::IsNullOrWhiteSpace($password)) {
        throw 'A senha não pode estar vazia.'
    }

    [System.Security.Cryptography.RandomNumberGenerator]::Fill($salt)
    $passwordBytes = [Text.Encoding]::UTF8.GetBytes($password)
    $derived = [System.Security.Cryptography.Rfc2898DeriveBytes]::Pbkdf2(
        $passwordBytes, $salt, 310000, [System.Security.Cryptography.HashAlgorithmName]::SHA256, 32)
    $base64Salt = [Convert]::ToBase64String($salt).TrimEnd('=')
    $base64Hash = [Convert]::ToBase64String($derived).TrimEnd('=')
    $encodedPassword = "pbkdf2-sha256`$310000`$$base64Salt`$$base64Hash"

    $lines = [System.IO.File]::ReadAllLines($environmentPath)
    $settings = [ordered]@{ ADMIN_USERNAME = $username; ADMIN_PASSWORD_HASH = $encodedPassword }
    foreach ($setting in $settings.GetEnumerator()) {
        $pattern = '^' + [regex]::Escape($setting.Key) + '='
        $found = $false
        for ($i = 0; $i -lt $lines.Length; $i++) {
            if ($lines[$i] -match $pattern) {
                $lines[$i] = $setting.Key + '=' + $setting.Value
                $found = $true
            }
        }
        if (-not $found) {
            $lines += $setting.Key + '=' + $setting.Value
        }
    }
    [System.IO.File]::WriteAllLines($environmentPath, $lines, [Text.UTF8Encoding]::new($false))
    Write-Output "Credenciais administrativas atualizadas em $EnvironmentFile."
}
finally {
    if ($passwordPointer -ne [IntPtr]::Zero) {
        [Runtime.InteropServices.Marshal]::ZeroFreeBSTR($passwordPointer)
    }
    if ($passwordBytes) { [Array]::Clear($passwordBytes, 0, $passwordBytes.Length) }
    if ($salt) { [Array]::Clear($salt, 0, $salt.Length) }
    if ($derived) { [Array]::Clear($derived, 0, $derived.Length) }
    if ($securePassword) { $securePassword.Dispose() }
    Remove-Variable password, encodedPassword, base64Salt, base64Hash -ErrorAction SilentlyContinue
}
