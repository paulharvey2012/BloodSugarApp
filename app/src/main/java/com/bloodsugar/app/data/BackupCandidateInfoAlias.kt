package com.bloodsugar.app.data

// Backwards-compatible typealias: some code refers to com.bloodsugar.app.data.BackupCandidateInfo
// while the actual data class is BackupInfo. Provide a top-level alias
// so existing references compile without changing many files.

typealias BackupCandidateInfo = BackupInfo
