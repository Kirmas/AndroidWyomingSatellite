# Android Wyoming Satellite

Wyoming Protocol satellite для Home Assistant на Android.

## Можливості

- 🎤 Wake word detection з ONNX Runtime
- 🔊 Аудіо захоплення та відтворення
- 🌐 Wyoming Protocol TCP client
- 🏠 Інтеграція з Home Assistant
- ⚡ Foreground service для фонової роботи

## Вимоги

- Android 8.0+ (API 26+)
- Дозволи: RECORD_AUDIO, INTERNET, FOREGROUND_SERVICE
- Home Assistant з Wyoming Protocol

## Архітектура

```
┌─────────────────┐
│  MainActivity   │ ─── UI, settings, permissions
└────────┬────────┘
         │
┌────────▼────────┐
│ WyomingService  │ ─── Foreground service
└────────┬────────┘
         │
    ┌────┴─────┬──────────────┬─────────────┐
    │          │              │             │
┌───▼───┐  ┌──▼──┐  ┌────────▼────┐  ┌─────▼─────┐
│ Audio │  │ TCP │  │ Wake Word   │  │ Protocol  │
│ I/O   │  │ Sock│  │ Detector    │  │ Handler   │
└───────┘  └─────┘  └─────────────┘  └───────────┘
```

## Налаштування

1. Встановити VS Code розширення:
   - Java Extension Pack
   - Kotlin Language Support
   - Gradle for Java
   - Android

2. Встановити залежності:
   - JDK 17
   - Android SDK Platform 34
   - Build-tools 34.0.0

3. Зібрати проект:
```bash
./gradlew assembleDebug
```

4. Встановити на пристрій:
```bash
adb install app/build/outputs/apk/debug/app-debug.apk
```

## Використання

1. Запустити додаток
2. Ввести IP адресу Home Assistant сервера
3. Встановити порт (за замовчуванням 10700)
4. Натиснути "Start Satellite"
5. Дозволити необхідні permissions
6. Промовити wake word для активації

## Розробка

Проект створено в VS Code без Android Studio.

**Основні файли:**
- `MainActivity.kt` - головний UI
- `WyomingService.kt` - фоновий сервіс
- `WyomingClient.kt` - TCP клієнт для Wyoming протоколу
- `AudioProcessor.kt` - обробка аудіо (запис/відтворення)
- `WakeWordDetector.kt` - детекція wake word через ONNX

**Build команди:**
```bash
# Debug build
gradlew assembleDebug

# Release build
gradlew assembleRelease

# Install
gradlew installDebug

# Clean
gradlew clean
```

## Ліцензія

MIT

## Автори

Розроблено з використанням:
- [ONNX Runtime](https://onnxruntime.ai/)
- [Wyoming Protocol](https://github.com/rhasspy/wyoming)
- [OpenWakeWord](https://github.com/dscripka/openWakeWord)
