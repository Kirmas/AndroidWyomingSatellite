# Wyoming Satellite для Android

Android додаток для інтеграції з Home Assistant через Wyoming протокол. Працює як голосовий сателіт з локальною детекцією wake word.

## Функції

- ✅ Локальна детекція wake word (openWakeWord + ONNX Runtime)
- ✅ Wyoming протокол TCP клієнт
- ✅ Захоплення аудіо (16kHz mono PCM)
- ✅ Відтворення TTS відповідей
- ✅ Foreground Service для роботи у фоні
- ⏳ VAD (Voice Activity Detection) - планується
- ⏳ UI для налаштувань - базовий

## Вимоги

- Android 8.0+ (API 26+)
- Мікрофон
- З'єднання з Home Assistant (Wyoming server)

## Побудова проекту

### З VS Code

```powershell
# Перевірка середовища
$env:JAVA_HOME
$env:ANDROID_HOME
java -version
sdkmanager --list

# Build
cd D:\Development\AndroidWyomingSatellite
.\gradlew.bat assembleDebug

# Install на пристрій
.\gradlew.bat installDebug

# Або через ADB
adb install app\build\outputs\apk\debug\app-debug.apk
```

### З Android Studio

1. Open Project → `D:\Development\AndroidWyomingSatellite`
2. Build → Build Bundle(s) / APK(s) → Build APK(s)
3. Run → Run 'app'

## Налаштування

1. Запустити Wyoming server на Home Assistant
2. Відкрити додаток на Android
3. Ввести IP адресу Home Assistant
4. Ввести порт Wyoming server (за замовчуванням 10700)
5. Натиснути "Start Service"
6. Надати дозволи на мікрофон та сповіщення

## Архітектура

```
MainActivity.kt - UI для налаштувань
├── WyomingService.kt - Foreground Service
    ├── WyomingClient.kt - TCP клієнт (JSON протокол)
    ├── AudioProcessor.kt - Захоплення/відтворення аудіо
    └── WakeWordDetector.kt - ONNX Runtime inference
```

## TODO

- [ ] Додати ONNX моделі в assets/models/
- [ ] Реалізувати повний Wyoming protocol state machine
- [ ] Додати VAD (Voice Activity Detection)
- [ ] Покращити UI (Material Design 3)
- [ ] Додати налаштування wake word threshold
- [ ] Додати візуалізацію рівня звуку
- [ ] Додати історію команд
- [ ] Оптимізація батареї
- [ ] Unit tests
- [ ] CI/CD (GitHub Actions)

## Залежності

- ONNX Runtime Android 1.16.3
- Kotlin Coroutines 1.7.3
- AndroidX Core, AppCompat, Material

## Ліцензія

MIT

## Посилання

- [Wyoming Protocol](https://github.com/rhasspy/wyoming)
- [openWakeWord](https://github.com/dscripka/openWakeWord)
- [ONNX Runtime](https://onnxruntime.ai/)
- [Home Assistant Voice](https://www.home-assistant.io/voice_control/)
