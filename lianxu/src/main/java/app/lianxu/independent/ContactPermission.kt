package app.lianxu.independent

internal sealed interface ContactPermission {
    data object Allowed:ContactPermission
    data object Denied:ContactPermission
}
