# Nextcloud Sync - Android Application

A native Android application for synchronizing files between your Android device and Nextcloud server using WebDAV.

## Features

- **Two-way Synchronization**: Sync files bidirectionally between device and Nextcloud
- **WebDAV Protocol**: Uses standard WebDAV for reliable file transfer
- **Material Design 3**: Modern, beautiful UI following Material Design guidelines
- **MVC Architecture**: Clean separation of concerns for maintainable code
- **Encryption at Rest**: SQLCipher-encrypted database for sensitive data
- **Encryption in Transit**: TLS 1.2+ enforced for all network communication
- **2FA Support**: Full two-factor authentication support with app passwords
- **Conflict Resolution**: User-prompted conflict resolution with multiple options
- **Background Sync**: Automatic periodic sync using WorkManager
- **Folder Selection**: Choose specific folders to sync
- **Manual Sync**: Trigger sync on demand

## Architecture

### Technology Stack

- **Language**: Kotlin
- **UI**: XML Layouts with Material Design 3 Components
- **Architecture**: Model-View-Controller (MVC)
- **Database**: Room + SQLCipher for encrypted persistence
- **Network**: OkHttp + Sardine (WebDAV client)
- **Background Tasks**: WorkManager for periodic sync
- **Security**: Android Keystore, EncryptedSharedPreferences

### Project Structure

```
app/src/main/java/com/nextcloud/sync/
├── controllers/          # Business logic layer
│   ├── auth/            # Authentication controllers
│   └── sync/            # Sync controllers
├── models/              # Data layer
│   ├── data/            # Data classes and enums
│   ├── database/        # Room entities, DAOs, database
│   ├── network/         # WebDAV client, API models
│   └── repository/      # Data repositories
├── views/               # Presentation layer
│   ├── activities/      # Android activities
│   ├── adapters/        # RecyclerView adapters
│   └── fragments/       # Fragment views (placeholder)
├── services/            # Background services
│   └── workers/         # WorkManager workers
├── utils/               # Utility classes
└── Application.kt       # Application class
```

## Build Instructions

### Prerequisites

- Android Studio Ladybug or later
- JDK 17
- Android SDK with API level 35
- Minimum target device: Android 8.0 (API 26)

### Building the Project

1. **Clone or navigate to the project directory**:
   ```bash
   cd /home/dougie/Projects/claude/sync
   ```

2. **Open in Android Studio**:
   - File → Open → Select the project directory
   - Android Studio will automatically sync Gradle

3. **Build the project**:
   ```bash
   ./gradlew build
   ```

4. **Run on device/emulator**:
   ```bash
   ./gradlew installDebug
   ```

### Build Variants

- **Debug**: Development build with logging enabled
- **Release**: Production build with ProGuard/R8 optimization

## Security Features

### Encryption at Rest

- **Database**: AES-256 encryption via SQLCipher
- **Passwords**: Encrypted using Android Keystore (hardware-backed when available)
- **App Passwords**: Securely stored in encrypted database
- **Encryption Keys**: Generated and stored in EncryptedSharedPreferences

### Encryption in Transit

- **TLS 1.2+**: Enforced for all network communication
- **Certificate Validation**: Validates server certificates
- **No Cleartext Traffic**: Network Security Config enforces HTTPS

### Authentication Security

- **Password Encryption**: AES-GCM with Android Keystore
- **Two-Factor Authentication**: Supports Nextcloud 2FA with app passwords
- **Session Management**: Secure token storage

## Usage

### Initial Setup

1. **Launch the app** - You'll be presented with the login screen
2. **Enter your Nextcloud details**:
   - Server URL (e.g., `https://cloud.example.com`)
   - Username
   - Password
3. **Two-Factor Authentication** (if enabled):
   - Enter the 6-digit code from your authenticator app
   - App will generate and store an app password

### Folder Selection

1. Open the app and navigate to "Folders"
2. Select folders from your Nextcloud server to sync
3. Configure sync options:
   - Two-way sync (upload and download) or download-only
   - WiFi-only sync to save mobile data

### Manual Sync

- Tap the **floating action button** (sync icon) on the main screen
- Sync will start immediately

### Conflict Resolution

When the same file is modified on both the device and server:

1. You'll receive a notification about conflicts
2. Open "Conflicts" from the menu
3. For each conflict, choose:
   - **Keep Local**: Upload your device version
   - **Keep Remote**: Download server version
   - **Keep Both**: Rename local file and keep both versions

## Configuration

### Sync Interval

Default: 15 minutes (WorkManager minimum)

To change, edit `Constants.kt`:
```kotlin
const val SYNC_INTERVAL_MINUTES = 15L
```

### Network Security

Edit `app/src/main/res/xml/network_security_config.xml` to customize:
- Certificate pinning
- Custom trust anchors
- Domain-specific configurations

## Troubleshooting

### Build Errors

**Issue**: Gradle sync fails
- **Solution**: File → Invalidate Caches → Invalidate and Restart

**Issue**: SQLCipher linking errors
- **Solution**: Clean and rebuild: `./gradlew clean build`

### Runtime Issues

**Issue**: Login fails with "Connection failed"
- Check server URL format (must include `http://` or `https://`)
- Verify network connectivity
- Check Nextcloud server is accessible

**Issue**: 2FA not working
- Ensure you're entering the current 6-digit code
- Check time synchronization on your device
- Verify 2FA is enabled in Nextcloud settings

**Issue**: Files not syncing
- Check background sync is enabled
- Verify network connectivity
- Check app permissions (Storage, Network)
- Review notification for errors

## Permissions

### Required Permissions

- **INTERNET**: Network communication with Nextcloud server
- **ACCESS_NETWORK_STATE**: Check network connectivity
- **FOREGROUND_SERVICE**: Background sync operations
- **POST_NOTIFICATIONS**: Sync status and conflict notifications

### Storage Permissions (Android 12 and below)

- **READ_EXTERNAL_STORAGE**: Read files to sync
- **WRITE_EXTERNAL_STORAGE**: Save synced files

**Note**: On Android 13+, the app uses scoped storage and doesn't require explicit storage permissions.

## Development

### Adding New Features

1. **Model Layer**: Create entities/data classes in `models/`
2. **Repository Layer**: Add repository in `models/repository/`
3. **Controller Layer**: Implement business logic in `controllers/`
4. **View Layer**: Create activities/layouts in `views/`

### Testing

Run unit tests:
```bash
./gradlew test
```

Run instrumentation tests:
```bash
./gradlew connectedAndroidTest
```

## Known Limitations

- Single account support (multi-account planned for future)
- No selective file type filtering yet
- Conflict resolution requires manual intervention
- No file versioning support
- Background sync limited to 15-minute minimum interval (WorkManager constraint)

## Future Enhancements

- [ ] Multi-account support
- [ ] Selective sync by file type/size
- [ ] Automatic conflict resolution for non-overlapping changes
- [ ] Media auto-backup
- [ ] File sharing via Nextcloud API
- [ ] Dark theme
- [ ] Biometric authentication
- [ ] End-to-end encryption support

## License

This project is for demonstration purposes. Please ensure compliance with your Nextcloud server's terms of service and applicable laws when using this application.

## Credits

### Libraries Used

- [Room](https://developer.android.com/training/data-storage/room) - Database
- [SQLCipher](https://www.zetetic.net/sqlcipher/) - Database encryption
- [Sardine-Android](https://github.com/thegrizzlylabs/sardine-android) - WebDAV client
- [OkHttp](https://square.github.io/okhttp/) - HTTP client
- [WorkManager](https://developer.android.com/topic/libraries/architecture/workmanager) - Background tasks
- [Material Components](https://material.io/develop/android) - UI components

## Support

For issues and questions:
1. Check the Troubleshooting section
2. Review Nextcloud server logs
3. Check Android logcat for detailed error messages

---

**Built with ❤️ using Kotlin, Material Design 3, and the MVC pattern**
