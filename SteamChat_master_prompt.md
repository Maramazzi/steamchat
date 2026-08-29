# SteamChat — мастер-промт для разработки Android-клиента Steam Chat

## Роль

Ты — senior Android engineer, software architect, reverse-engineering engineer, code reviewer и technical researcher.

Твоя задача — спроектировать и затем поэтапно реализовать **SteamChat**: удобный нативный Android-клиент для общения через Steam.

Идея проекта:

> Использовать существующий open-source Telegram-подобный Android-клиент как готовую UI/UX-базу, а Telegram backend заменить собственной абстракцией и интеграцией со Steam, чтобы приложение стало удобной оболочкой над Steam Chat.

Главная цель — **не писать всё с нуля**. Максимально переиспользовать зрелый UI, компоненты, навигацию, темы, работу с изображениями, уведомлениями и другие готовые решения.

---

# 0. Критическое правило: сначала исследование, потом код

НЕ начинай писать код сразу.

Сначала проведи техническое исследование актуальных исходников и документации.

Твоя первая задача — определить **какую именно кодовую базу лучше использовать** и **какую архитектуру выбрать**.

Если первоначальная идея окажется плохой — измени её и объясни почему.

Не нужно следовать моим предположениям, если исходники показывают более правильный путь.

---

# 1. Кандидаты для UI-базы

Обязательно сравни минимум следующие проекты:

### A. Nekogram

https://github.com/Nekogram/Nekogram

### B. Telegram Android

https://github.com/DrKLO/Telegram

### C. Telegram-FOSS

https://github.com/Telegram-FOSS-Team/Telegram-FOSS

Также можешь найти и рассмотреть другие качественные open-source Telegram-клиенты, если они потенциально лучше подходят для задачи.

Например, исследуй актуальные Android-форки и оцени их только по реальным исходникам и лицензиям.

---

# 2. Сравнение UI-баз

Составь подробную сравнительную таблицу.

Оцени каждый кандидат по:

| Критерий | Вес |
|---|---:|
| Простота удаления Telegram backend | 10 |
| Качество Android UI | 10 |
| Архитектура | 10 |
| Простота модификации | 10 |
| Актуальность проекта | 9 |
| Качество исходников | 9 |
| Документированность | 7 |
| Система сообщений | 8 |
| Система диалогов | 8 |
| Система пользователей | 7 |
| Кэш/Storage | 7 |
| Notifications | 7 |
| Media | 6 |
| Themes | 6 |
| Gradle/build system | 6 |
| Лицензия | 10 |
| Простота дальнейшей поддержки | 10 |

Поставь каждому проекту оценку от 1 до 10.

После таблицы сделай итоговый рейтинг.

---

# 3. Самостоятельный выбор базы

Ты обязан **сам выбрать лучший вариант**.

Не говори просто:

> "Nekogram выглядит неплохо."

Нужно написать:

```text
МОЙ ВЫБОР:
<проект>

ПОЧЕМУ:
1. ...
2. ...
3. ...
4. ...

АЛЬТЕРНАТИВЫ:
1. ...
2. ...

ПОЧЕМУ ИХ НЕ ВЫБРАЛ:
...
```

Если другой проект объективно лучше Nekogram — выбирай его.

Если необходимо взять не один проект, а комбинацию компонентов, объясни это.

---

# 4. Проверка лицензий

Для каждого кандидата изучи реальные LICENSE/COPYING файлы и условия распространения.

Отдельно проверить:

- GPL;
- Apache;
- MIT;
- дополнительные лицензии;
- зависимости;
- требования по исходному коду;
- attribution;
- copyright notices;
- trademark;
- название Telegram;
- логотипы;
- требования при публикации модифицированной версии.

Не делай юридических выводов без проверки актуальных файлов проекта.

Сделай таблицу:

| Проект | Лицензия | Можно модифицировать | Можно распространять | Что нужно сохранить |
|---|---|---|---|---|

В конце предложи наиболее безопасную с точки зрения лицензирования архитектуру.

---

# 5. Steam backend

Исследуй актуальный:

## SteamKit2

https://github.com/SteamRE/SteamKit

Не ограничивайся README.

Изучи реальные исходники.

Особое внимание:

- SteamClient;
- SteamUser;
- SteamFriends;
- authentication;
- Steam Guard;
- callbacks;
- protobuf;
- chat-related handlers;
- friend messages;
- chat rooms;
- message history;
- realtime events.

Найди конкретные классы, методы, callbacks и события.

---

# 6. Проверить возможность прямой работы на Android

Это один из главных вопросов проекта.

Проверь:

> Можно ли сейчас нормально использовать SteamKit2/.NET непосредственно внутри Android-приложения?

Исследуй:

- .NET for Android;
- совместимость SteamKit2;
- target frameworks;
- networking;
- sockets;
- TLS;
- protobuf;
- threading;
- background execution;
- Android lifecycle;
- foreground service;
- battery;
- process death;
- reconnect;
- Android restrictions;
- ARM64;
- APK/AAB;
- Google Play compatibility.

После исследования выбери один вариант:

### Вариант A

```text
Android
 ↓
SteamKit2
 ↓
Steam
```

### Вариант B

```text
Android
 ↓
Backend
 ↓
SteamKit2
 ↓
Steam
```

### Вариант C

Другой вариант, если он объективно лучше.

Не выбирай backend просто потому, что он привычнее.

Если прямой Android-вариант реалистичен — предпочесть его для MVP.

---

# 7. Главный архитектурный принцип

UI не должен зависеть напрямую от SteamKit2.

Использовать:

```text
UI
 ↓
ViewModel
 ↓
Repository
 ↓
SteamService
 ↓
SteamKit2
 ↓
Steam
```

SteamKit2 должен быть изолирован.

UI не должен знать о:

- protobuf;
- Steam callbacks;
- Steam client protocol;
- внутренних классах SteamKit2.

---

# 8. Собственные Domain Models

Создай собственные модели.

Например:

```kotlin
data class SteamUser(
    val steamId: String,
    val name: String,
    val avatarUrl: String?,
    val status: SteamStatus
)

data class SteamDialog(
    val id: String,
    val user: SteamUser,
    val lastMessage: SteamMessage?,
    val unreadCount: Int
)

data class SteamMessage(
    val id: String,
    val chatId: String,
    val senderId: String,
    val text: String,
    val timestamp: Long,
    val isOutgoing: Boolean
)
```

Это только пример.

После анализа исходников сам выбери правильную модель.

---

# 9. SteamService

Спроектируй единый интерфейс:

```kotlin
interface SteamService {

    suspend fun login(...)

    suspend fun logout()

    suspend fun getCurrentUser()

    suspend fun getFriends()

    suspend fun getDialogs()

    suspend fun getMessages(chatId: String)

    suspend fun sendMessage(chatId: String, text: String)

    suspend fun markAsRead(chatId: String)

    fun observeNewMessages()

    fun observeFriendStatus()
}
```

Это пример, а не обязательная сигнатура.

Выбери лучший API после анализа.

---

# 10. Слой адаптации

Создай:

```text
SteamRepository
SteamService
SteamKitAdapter
```

или более подходящую структуру.

Главное:

```text
Nekogram UI
      ↓
Steam-specific domain
      ↓
SteamKit adapter
      ↓
Steam
```

Telegram API не должен протекать в новый backend.

---

# 11. Анализ выбранного Telegram-клиента

После выбора конкретной базы исследуй её исходники.

Найди:

- Application;
- Activities;
- Fragments;
- navigation;
- dialogs;
- chat screen;
- message rendering;
- users;
- database;
- media;
- avatars;
- notifications;
- themes;
- search;
- settings;
- background tasks.

Составь карту:

```text
Telegram component
        ↓
KEEP / ADAPT / REPLACE / REMOVE
```

Например:

```text
Chat UI              → KEEP + ADAPT
Dialogs UI           → KEEP + ADAPT
Theme system         → KEEP
Image loading        → KEEP
Notification UI      → ADAPT
Telegram networking  → REMOVE
Telegram auth        → REMOVE
Telegram models      → REPLACE
Telegram storage     → ADAPT/REPLACE
```

Не удаляй классы до анализа зависимостей.

---

# 12. Автоматическая стратегия миграции

Придумай безопасную стратегию перехода.

Не делай:

> удалить Telegram backend → всё сломалось → чинить 500 ошибок.

Вместо этого:

```text
Шаг 1 — создать новые Steam models
Шаг 2 — создать SteamRepository
Шаг 3 — создать fake implementation
Шаг 4 — подключить UI к repository
Шаг 5 — проверить UI без Telegram
Шаг 6 — подключить SteamKit2
Шаг 7 — удалить ненужный Telegram backend
Шаг 8 — удалить остаточные зависимости
```

Если найдёшь более безопасный путь — предложи его.

---

# 13. Fake backend

Обязательно создать:

```text
FakeSteamService
```

Он должен позволять запускать UI без Steam.

Например:

```text
5 friends
3 dialogs
50 messages
2 unread messages
online/offline states
```

Это позволит тестировать UI без Steam аккаунта.

---

# 14. Авторизация Steam

Исследуй актуальную Steam authentication architecture.

Нужно определить:

- username/password;
- Steam Guard;
- email code;
- Mobile Authenticator;
- QR login;
- session;
- refresh;
- logout;
- expired session;
- reconnect.

Выбери лучший UX.

Например:

```text
Steam login
     ↓
Steam Guard
     ↓
authenticated
```

Если возможна безопасная QR-авторизация — исследуй её отдельно.

Никогда не хранить пароль plaintext.

---

# 15. Безопасность

Использовать Android Keystore там, где это необходимо.

НЕ:

- логировать пароль;
- логировать Steam Guard;
- сохранять пароль plaintext;
- отправлять credentials на сторонний сервер без необходимости;
- вставлять секреты в Git;
- хранить API keys в исходниках.

Продумать:

- session storage;
- logout;
- token invalidation;
- app backup;
- screenshots;
- clipboard;
- logs.

---

# 16. Реальное время

Новые сообщения должны приходить через события SteamKit2, если это возможно.

Желаемый поток:

```text
Steam
 ↓
SteamKit2 event
 ↓
SteamService
 ↓
Repository
 ↓
Flow/StateFlow
 ↓
ViewModel
 ↓
UI
```

Не использовать polling, если существует нормальный event-based механизм.

---

# 17. Локальное хранилище

Исследуй существующий storage выбранного Telegram-клиента.

Сравни:

- его storage;
- Room;
- SQLite;
- другой вариант.

Сам выбери лучший.

Хранить:

```text
users
dialogs
messages
unread counters
last messages
timestamps
avatars/cache
```

Не хранить Steam password.

---

# 18. Notifications

Реализовать:

```text
Friend
Новое сообщение:
"Привет, го играть?"
```

Нажатие:

```text
Notification
 ↓
SteamChat
 ↓
ChatActivity
 ↓
конкретный диалог
```

Учитывать:

- Android 13+;
- notification permission;
- background;
- foreground;
- duplicate notifications;
- unread count;
- notification grouping;
- reconnect;
- process death.

---

# 19. UI/UX

Приложение должно ощущаться как:

> Telegram-подобный мессенджер, но полностью ориентированный на Steam.

Не копировать Telegram branding.

Не использовать название/логотип Telegram так, будто приложение официальное.

Использовать привычные UX-паттерны:

- список диалогов;
- аватарки;
- unread badges;
- swipe back;
- long press;
- context menu;
- search;
- dark theme;
- AMOLED;
- smooth animations;
- edge-to-edge;
- быстрый переход в профиль;
- game status;
- Steam profile.

---

# 20. MVP

Не реализовывать всё сразу.

Первый MVP должен содержать только:

## Login

```text
Steam login
Steam Guard
```

## Friends

```text
Avatar
Name
Online status
```

## Chat

```text
History
Input
Send
Receive
```

Рабочая цепочка:

```text
Login
 ↓
Friends
 ↓
Select friend
 ↓
Open chat
 ↓
Load history
 ↓
Send message
 ↓
Receive message
```

Если эта цепочка работает — MVP считается успешным.

---

# 21. MVP-2

Добавить:

- dialogs;
- last message;
- unread counters;
- avatars;
- statuses;
- realtime;
- notifications;
- timestamps;
- message grouping;
- search;
- offline cache.

---

# 22. MVP-3

Добавить:

- group chats;
- media;
- images;
- links;
- Steam profiles;
- game status;
- Rich Presence;
- block/unblock;
- friend management;
- game launching;
- deep links.

---

# 23. Функции, которые нужно исследовать отдельно

Определи реальную поддержку SteamKit2 для:

```text
Private messages
Message history
Offline messages
Group chats
Chat rooms
Typing indicator
Read state
Friend status
Presence
Avatars
Profile
Rich Presence
Images
Links
Emojis
Reactions
Editing
Deletion
Replies
Forwarding
Blocking
Friend requests
```

Для каждого:

| Функция | Поддержка | SteamKit2 API | Ограничения | Сложность |
|---|---|---|---|---|

Не придумывай отсутствующие API.

---

# 24. Сравнение с официальным Steam Chat

После MVP сравни:

- скорость;
- UI;
- удобство;
- уведомления;
- история;
- поиск;
- расход батареи;
- стабильность;
- авторизация.

Определи, где можно сделать приложение лучше официального клиента.

---

# 25. Архитектура offline-first

Продумай:

```text
UI
 ↓
ViewModel
 ↓
Repository
 ↓
Local DB
 ↕
SteamService
 ↓
SteamKit2
 ↓
Steam
```

При запуске:

```text
Local data
 ↓
показываем UI
 ↓
подключаем Steam
 ↓
синхронизируем
 ↓
обновляем UI
```

Не заставлять пользователя ждать полной синхронизации, если данные уже есть в cache.

---

# 26. Reconnect

Обязательно:

```text
Connected
 ↓
Connection lost
 ↓
Backoff
 ↓
Reconnect
 ↓
Restore session
 ↓
Synchronize
```

Обработать:

- internet lost;
- Steam disconnected;
- Android process restart;
- app resume;
- authentication expired;
- server unavailable;
- duplicate events;
- duplicate messages;
- missing history;
- rate limits.

---

# 27. Производительность

Проверить:

- startup time;
- memory;
- battery;
- message rendering;
- large chats;
- avatar caching;
- database queries;
- network reconnect;
- background behavior.

Не тащить всю историю чата в память.

Использовать pagination.

---

# 28. Кодстайл

Следовать современному Android/Kotlin style.

Предпочтительно:

- Kotlin;
- Coroutines;
- Flow;
- StateFlow;
- dependency injection, если оправдан;
- clean separation;
- immutable UI state.

Но сначала изучить архитектуру выбранной базы.

Не переписывать проект в новый architecture style без необходимости.

---

# 29. Git

Использовать маленькие осмысленные commits:

```text
init: import selected client base

feat: add Steam domain models

feat: add Steam repository

feat: add fake Steam service

feat: implement Steam authentication

feat: implement friends

feat: implement dialogs

feat: implement message history

feat: implement send message

feat: implement realtime messages

feat: implement notifications

refactor: remove Telegram backend dependencies
```

После каждого значимого шага проект должен собираться.

---

# 30. Testing

Создать:

```text
unit tests
repository tests
Steam adapter tests
fake backend
UI tests
```

Особенно тестировать:

- login;
- reconnect;
- duplicate messages;
- history pagination;
- unread count;
- message sending;
- offline mode;
- process restart.

---

# 31. Работа с исходниками

Перед изменением файла:

1. прочитай его;
2. найди зависимости;
3. найди callers;
4. пойми lifecycle;
5. только потом меняй.

Не делать массовый search-and-replace.

Не удалять классы наугад.

После каждого этапа:

```text
compile
 ↓
test
 ↓
inspect errors
 ↓
fix
 ↓
compile again
```

---

# 32. Что делать, если архитектура выбранного форка плохая

Ты имеешь право отказаться от первоначального выбора.

Если выяснится, что:

- Nekogram слишком сильно связан с Telegram;
- Telegram-FOSS проще отделить;
- оригинальный Telegram Android лучше поддерживается;
- другой open-source клиент имеет значительно более модульную архитектуру;

то выбери лучший вариант.

Обязательно объясни:

```text
Первоначальная идея:
Nekogram

После исследования:
<новый выбор>

Причина:
...
```

---

# 33. Что делать, если SteamKit2 плохо подходит

Не пытайся насильно использовать библиотеку.

Исследуй альтернативы:

- другие open-source Steam client libraries;
- официальные Steam API;
- Steam Client Protocol implementations;
- другие .NET библиотеки;
- Kotlin/Java решения;
- собственный минимальный adapter.

Но если выбирается нестандартная реализация Steam-протокола — отдельно оцени:

- стабильность;
- поддержку;
- безопасность;
- лицензию;
- сложность;
- maintenance.

---

# 34. Что нейронка должна сама придумать

Ты НЕ обязан следовать моим названиям классов или технологий.

Самостоятельно выбери:

### UI framework

XML / Views / Compose / существующий UI.

### Architecture

MVVM / MVI / Clean / существующая архитектура проекта.

### Database

Room / SQLite / существующее хранилище.

### DI

Hilt / Koin / manual DI / существующая система.

### Networking

SteamKit2 / другой adapter.

### Reactive layer

Flow / LiveData / существующий механизм.

### Notifications

существующая система / собственная реализация.

### Authentication

лучший безопасный способ, который реально поддерживается.

Для каждого выбора объясни:

```text
Выбрано:
...

Почему:
...

Альтернативы:
...

Почему не они:
...
```

---

# 35. Финальный технический отчёт перед кодом

В самом начале работы выдай отчёт со следующей структурой:

# 1. Executive Summary

Что мы строим и какой подход выбран.

# 2. Candidate Comparison

Сравнение Nekogram / Telegram Android / Telegram-FOSS / других найденных кандидатов.

# 3. Final Base Selection

Какой проект выбран и почему.

# 4. License Analysis

Лицензии и ограничения.

# 5. SteamKit2 Analysis

Что реально умеет библиотека.

# 6. Android Compatibility

Можно ли запускать её непосредственно на Android.

# 7. Architecture

Полная схема.

# 8. Telegram → Steam Migration Map

Таблица:

```text
KEEP
ADAPT
REPLACE
REMOVE
```

# 9. Domain Model

Все основные модели.

# 10. SteamService API

Интерфейсы.

# 11. Authentication Flow

Авторизация.

# 12. Message Flow

Получение и отправка.

# 13. Storage

Как работает cache.

# 14. Notifications

Как работает background.

# 15. MVP Roadmap

Пошаговый план.

# 16. Risks

Все технические и лицензионные риски.

# 17. Final Recommendation

Чёткий ответ:

> Как именно ты предлагаешь строить SteamChat и почему.

---

# 36. Формат плана разработки

После исследования разбей реализацию на этапы.

Каждый этап должен выглядеть так:

```text
## Stage X — название

### Цель
...

### Файлы
...

### Изменения
...

### Новые классы
...

### Зависимости
...

### Проверка
...

### Ожидаемый результат
...
```

Не давать сразу 100 файлов кода.

---

# 37. Definition of Done

Проект считается готовым для первого релиза, если пользователь может:

```text
1. Установить приложение
2. Авторизоваться через Steam
3. Пройти Steam Guard
4. Увидеть друзей
5. Увидеть их статусы
6. Открыть диалог
7. Загрузить историю
8. Отправить сообщение
9. Получить сообщение в realtime
10. Получить уведомление
11. Закрыть приложение
12. Открыть его снова
13. Продолжить переписку
14. Пережить временное отключение интернета
```

---

# 38. Ключевой принцип проекта

> **Не изобретай то, что уже существует.**

Используй:

- зрелую Android UI-базу;
- готовые компоненты;
- существующую систему тем;
- существующие notification-механизмы;
- SteamKit2 или лучший найденный Steam client implementation.

Но не тащи ненужный Telegram backend только потому, что он уже есть.

---

# 39. Второй ключевой принцип

> **Не пиши код до понимания архитектуры.**

Сначала:

```text
Research
 ↓
Compare
 ↓
Choose
 ↓
Design
 ↓
Prototype
 ↓
Implement
 ↓
Test
 ↓
Refactor
```

---

# 40. Третий ключевой принцип

> **Если есть более хороший вариант — предложи его самостоятельно.**

Я даю направление, а не жёсткое техническое задание.

Ты должен критически оценивать мои предположения.

Если найдёшь способ:

- проще;
- быстрее;
- стабильнее;
- безопаснее;
- дешевле в поддержке;

предложи его.

---

# 41. Итоговая задача

Создать максимально удобный Android-клиент:

# SteamChat

Который ощущается как современный Telegram-подобный мессенджер, но работает с аккаунтом Steam и Steam Chat.

Цель:

```text
              SteamChat
                  │
        ┌─────────┴─────────┐
        │                   │
      UI/UX             Steam backend
        │                   │
  Telegram-like        SteamKit2/
     client           best alternative
        │                   │
        └─────────┬─────────┘
                  │
                Steam
```

Приложение не должно выдавать себя за официальный Telegram или официальный Steam-клиент.

---

# НАЧАЛО РАБОТЫ

Начни только с исследования.

Не создавай файлы и не пиши реализацию до выдачи технического отчёта.

Твои первые действия:

1. Изучить Nekogram.
2. Изучить Telegram Android.
3. Изучить Telegram-FOSS.
4. При необходимости найти дополнительные open-source кандидаты.
5. Сравнить их архитектуру.
6. Проверить лицензии.
7. Изучить SteamKit2.
8. Проверить актуальную Android/.NET совместимость.
9. Проверить реальные возможности Steam Chat.
10. Выбрать UI-базу.
11. Выбрать backend-подход.
12. Спроектировать архитектуру.
13. Составить MVP roadmap.
14. Только после этого начать реализацию.

**Не выдумывай API, классы или возможности библиотек. Если не уверен — проверь исходники.**
