# Version Management for BloodSugarApp

## Current Version: 1.0.0

This document explains how version numbers are managed in the BloodSugarApp.

## Version Display
The app displays the version number at the bottom of each screen:
- Add Reading Screen: Version footer at bottom
- History Screen: Version footer at bottom

## Version Configuration
Version is configured in `app/build.gradle`:
```gradle
versionCode 1
versionName "1.0.0"
```

## Updating Versions

### Manual Version Update
To update the version manually:
1. Edit `app/build.gradle`
2. Increment `versionCode` (integer, for Google Play)
3. Update `versionName` (string, displayed to users)

### Version Code Guidelines
- Increment `versionCode` by 1 for each build
- Update `versionName` following semantic versioning (MAJOR.MINOR.PATCH)

### Example Version Updates
```gradle
// Version 1.0.0 (Current)
versionCode 1
versionName "1.0.0"

// Version 1.0.1 (Bug fix)
versionCode 2
versionName "1.0.1"

// Version 1.1.0 (New feature)
versionCode 3
versionName "1.1.0"

// Version 2.0.0 (Major release)
versionCode 4
versionName "2.0.0"
```

## Automatic Version Display
The version is automatically displayed in the app using BuildConfig:
- `BuildConfig.VERSION_NAME` provides the version string
- Displayed via the `VersionFooter` component
- Updates automatically when version is changed in build.gradle
