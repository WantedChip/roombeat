package android.net

import android.os.Parcel

/**
 * Test implementation of [Uri] placed in package `android.net`
 * to access the package-private [Uri] constructor for headless JVM testing.
 */
class TestUri(
    private val uriString: String,
    private val pathSegment: String? = null
) : Uri() {
    override fun toString(): String = uriString
    override fun isHierarchical(): Boolean = true
    override fun isRelative(): Boolean = false
    override fun getScheme(): String = uriString.substringBefore(':')
    override fun getSchemeSpecificPart(): String = uriString.substringAfter(':')
    override fun getAuthority(): String = "media"
    override fun getUserInfo(): String? = null
    override fun getHost(): String? = null
    override fun getPort(): Int = -1
    override fun getPath(): String? = null
    override fun getQuery(): String? = null
    override fun getFragment(): String? = null
    override fun getPathSegments(): List<String> = emptyList()
    override fun getLastPathSegment(): String? = pathSegment ?: uriString.substringAfterLast('/')
    override fun getEncodedPath(): String? = null
    override fun getEncodedQuery(): String? = null
    override fun getEncodedFragment(): String? = null
    override fun getEncodedAuthority(): String? = null
    override fun getEncodedUserInfo(): String? = null
    override fun getEncodedSchemeSpecificPart(): String = ""
    override fun buildUpon(): Builder? = null
    override fun writeToParcel(dest: Parcel, flags: Int) {}
    override fun describeContents(): Int = 0
    override fun compareTo(other: Uri?): Int = uriString.compareTo(other?.toString() ?: "")

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is Uri) return false
        return uriString == other.toString()
    }

    override fun hashCode(): Int = uriString.hashCode()
}

