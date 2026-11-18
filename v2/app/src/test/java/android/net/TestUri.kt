package android.net

/**
 * Adresse minimale pour les tests sur la JVM : les classes Android n'y sont que des coquilles vides,
 * et Media3 refuse une adresse nulle. Le constructeur d'[Uri] n'étant visible que dans ce paquet, elle vit ici.
 */
class TestUri(private val text: String = "test://x") : Uri() {
    override fun buildUpon(): Builder = throw UnsupportedOperationException()
    override fun getAuthority(): String? = null
    override fun getEncodedAuthority(): String? = null
    override fun getEncodedFragment(): String? = null
    override fun getEncodedPath(): String? = null
    override fun getEncodedQuery(): String? = null
    override fun getEncodedSchemeSpecificPart(): String = text
    override fun getEncodedUserInfo(): String? = null
    override fun getFragment(): String? = null
    override fun getHost(): String? = null
    override fun getLastPathSegment(): String? = null
    override fun getPath(): String? = null
    override fun getPathSegments(): List<String> = emptyList()
    override fun getPort(): Int = -1
    override fun getQuery(): String? = null
    override fun getScheme(): String? = text.substringBefore(':')
    override fun getSchemeSpecificPart(): String = text
    override fun getUserInfo(): String? = null
    override fun isHierarchical(): Boolean = false
    override fun isRelative(): Boolean = false
    override fun toString(): String = text
    override fun describeContents(): Int = 0
    override fun writeToParcel(dest: android.os.Parcel, flags: Int) = Unit
}
