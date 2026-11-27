package io.github.univers629.millet.core

internal enum class ServiceRoleProtection {
    NONE,
    DEFAULT_IME,
    ACCESSIBILITY,
    UNKNOWN;

    val blocksFreeze: Boolean get() = this != NONE
}

internal data class UserServiceRoles(
    val imePackage: String?,
    val accessibilityPackages: Set<String>,
    val autofillPackage: String? = null,
)

internal fun evaluateUserServiceProtection(
    uid: Int,
    packageName: String?,
    readUserRoles: (Int) -> UserServiceRoles?,
): ServiceRoleProtection {
    if (!AppUidUtils.isManagedAppUid(uid) || packageName.isNullOrEmpty()) return ServiceRoleProtection.NONE
    // A null snapshot denotes a failed read; an empty snapshot is a known negative result.
    val roles = runCatching { readUserRoles(AppUidUtils.userIdOf(uid)) }.getOrNull()
        ?: return ServiceRoleProtection.UNKNOWN
    return when {
        packageName == roles.imePackage -> ServiceRoleProtection.DEFAULT_IME
        packageName in roles.accessibilityPackages -> ServiceRoleProtection.ACCESSIBILITY
        else -> ServiceRoleProtection.NONE
    }
}
