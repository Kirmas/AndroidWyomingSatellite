# TODO List - Wyoming Satellite

## Оптимізації Wake Word Detection

### Voice Activity Detection (VAD) з гістерезисом
**Пріоритет:** Середній  
**Статус:** Не реалізовано

**Опис:**
Додати систему відсікання тиші для економії CPU/батареї:
- Якщо виявили звук (RMS > 0.01) → продовжуємо обробку ще 5-10 секунд навіть якщо стало тихо
- Якщо тиша >5-10 секунд → переходимо в режим очікування, не запускаємо ONNX моделі
- Це дозволить не обробляювати ~80-90% часу коли тиша

**Реалізація:**
```kotlin
private var lastSoundTimestamp = 0L
private val SILENCE_TIMEOUT = 5000L // 5 секунд
private val RMS_THRESHOLD = 0.01f

fun detectWakeWord(audioChunk: ShortArray): Float? {
    // Швидкий RMS розрахунок
    val rms = sqrt(audioChunk.sumOf { (it / 32768.0).pow(2) } / audioChunk.size)
    val currentTime = System.currentTimeMillis()
    
    // Оновлюємо timestamp якщо є звук
    if (rms > RMS_THRESHOLD) {
        lastSoundTimestamp = currentTime
    }
    
    // Пропускаємо ONNX якщо тиша >5 сек
    if (currentTime - lastSoundTimestamp > SILENCE_TIMEOUT) {
        return null
    }
    
    // Інакше обробляємо як зараз
    val floatBuffer = FloatArray(audioChunk.size) { i ->
        audioChunk[i] / 32768.0f
    }
    return predictWakeWord(floatBuffer)
}
```

**Переваги:**
- ✅ Економія CPU (не запускаємо 3 ONNX моделі в тиші)
- ✅ Економія батареї
- ✅ Не пропустимо wake word після короткої паузи
- ✅ Продовжуємо слухати після звуку (можна catch фрази типу "Hey Luna, turn on the light")

**Недоліки:**
- ❌ Може пропустити дуже тихе "Hey Luna"
- ❌ Додаткові обчислення для RMS (але набагато швидше ніж ONNX)

**Параметри для налаштування:**
- `RMS_THRESHOLD` - поріг звуку (0.01-0.02)
- `SILENCE_TIMEOUT` - час очікування після звуку (5000-10000 ms)

---

### Multi-turn Conversation Mode
**Пріоритет:** Високий  
**Статус:** Не реалізовано

**Опис:**
Підтримка багатокрокового діалогу - коли сервер просить продовжити розмову БЕЗ повторного wake word.

**Сценарій:**
1. Користувач: "Hey Luna, what's the weather?"
2. HA: "It's 20 degrees. Do you want the forecast?" + `continue_conversation=true`
3. Користувач: "Yes" ← **БЕЗ wake word!**
4. HA відповідає з прогнозом

**Реалізація:**
```kotlin
enum class ListeningState {
    IDLE,              // Чекаємо wake word
    WAKE_WORD_DETECTED, // Записуємо після wake word
    CONVERSATION_MODE   // Діалог активний, БЕЗ wake word
}

var state = IDLE
var conversationTimeout = 0L

// Від сервера прийшло continue_conversation
fun onContinueConversation(timeoutMs: Long) {
    state = CONVERSATION_MODE
    conversationTimeout = System.currentTimeMillis() + timeoutMs
}

// В обробці аудіо
when (state) {
    IDLE -> {
        // Чекаємо wake word
        if (wakeWordDetected) state = WAKE_WORD_DETECTED
    }
    WAKE_WORD_DETECTED, CONVERSATION_MODE -> {
        // Записуємо і стрімимо аудіо
        streamAudioToServer()
        
        // Перевіряємо timeout в CONVERSATION_MODE
        if (state == CONVERSATION_MODE && 
            System.currentTimeMillis() > conversationTimeout) {
            state = IDLE
        }
    }
}
```

**Wyoming протокол підтримка:**
```json
{
    "type": "audio_stop",
    "continue_conversation": true,
    "timeout": 5000
}
```

---

## Інші можливі покращення

### 1. Візуальна індикація wake word
- Показувати анімацію/колір коли wake word виявлено
- Індикатор рівня звуку

### 2. Налаштування через UI
- Змінювати THRESHOLD (поріг детекції)
- Змінювати RMS_THRESHOLD
- Вибір wake word моделі

### 3. Інтеграція з Wyoming протоколом
- Після wake word - відправляти аудіо на Wyoming сервер
- Отримувати відповідь від ASR/TTS
- Програвати TTS відповідь

### 4. Додаткові wake word моделі
- hey_jarvis.onnx
- alexa.onnx
- ok_nabu.onnx
- Можливість завантажувати свої моделі

### 5. Статистика/логування
- Скільки разів спрацював wake word
- Середній час обробки
- Використання батареї
