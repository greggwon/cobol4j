package org.cobol4j;

/**
 * COBOL file organizations.
 */
public enum FileOrganization {
    /** Records accessed in order of writing. */
    SEQUENTIAL,
    /** Records accessed by one or more keys (like VSAM KSDS). */
    INDEXED,
    /** Records accessed by relative record number. */
    RELATIVE
}
