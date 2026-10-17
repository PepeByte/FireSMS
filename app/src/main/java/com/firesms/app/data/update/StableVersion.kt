package com.firesms.app.data.update

import java.math.BigInteger

/** Stable SemVer only. Build metadata does not change version precedence. */
@ConsistentCopyVisibility
internal data class StableVersion private constructor(
    val major: BigInteger,
    val minor: BigInteger,
    val patch: BigInteger
) : Comparable<StableVersion> {
    override fun compareTo(other: StableVersion): Int =
        compareValuesBy(this, other, StableVersion::major, StableVersion::minor, StableVersion::patch)

    companion object {
        private val pattern = Regex(
            """^v?(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)(?:\+[0-9A-Za-z-]+(?:\.[0-9A-Za-z-]+)*)?$"""
        )

        fun parse(value: String): StableVersion? {
            val match = pattern.matchEntire(value) ?: return null
            return StableVersion(
                match.groupValues[1].toBigInteger(),
                match.groupValues[2].toBigInteger(),
                match.groupValues[3].toBigInteger()
            )
        }
    }
}
