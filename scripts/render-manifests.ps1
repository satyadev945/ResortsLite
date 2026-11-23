param(
    [Parameter(Mandatory = $true)][string]$Path
)
# Replaces {{PLACEHOLDER}} tokens with values from DEPLOY_<NAME> environment variables.
# Values are escaped for use inside double-quoted YAML scalars.
$keys = @('IMAGE_URI','NAMESPACE','DB_URL','DB_USERNAME','DB_PASSWORD','REDIS_HOST','REDIS_PORT','REDIS_SSL','REDIS_PASSWORD','PAYMENT_ENDPOINT','INVENTORY_ENDPOINT','NOTIFICATION_ENDPOINT','INGRESS_HOST')
$content = [System.IO.File]::ReadAllText($Path)
foreach ($k in $keys) {
    $v = [Environment]::GetEnvironmentVariable("DEPLOY_$k")
    if ($null -eq $v) { $v = '' }
    $v = $v.Replace('\', '\\').Replace('"', '\"')
    $content = $content.Replace('{{' + $k + '}}', $v)
}
[System.IO.File]::WriteAllText($Path, $content, (New-Object System.Text.UTF8Encoding($false)))
