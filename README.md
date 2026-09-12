# SteamChatX

Неофициальный нативный Android-клиент для чата Steam: друзья, личные и групповые переписки,
голосовые сообщения, эмотиконы Steam, профиль и 1:1 голосовые звонки — в интерфейсе в духе
современных мессенджеров, а не веб-обёртки.

> **SteamChatX — независимый проект сообщества. Он не является официальным продуктом Valve
> Corporation, не связан с ней, не одобрен и не поддерживается ею.** Подробности — в разделе
> «Правовая информация» ниже.

---

## Правовая информация / Legal notice

**Русский**

- SteamChatX **не является официальным приложением Steam** и **никак не связан с Valve
  Corporation**. Valve не участвовала в разработке, не проверяла и не одобряла этот проект.
- **Steam**, логотип Steam и связанные названия — товарные знаки и/или зарегистрированные
  товарные знаки Valve Corporation в США и других странах. В этом проекте они используются
  исключительно в описательных целях (чтобы обозначить, с каким сервисом работает клиент), а
  не как указание на происхождение, партнёрство или одобрение.
- Проект **не содержит** исходного кода, ресурсов или проприетарных материалов Valve. Клиент
  общается с серверами Steam через открытую библиотеку [JavaSteam](https://github.com/Longi94/JavaSteam)
  (JVM-порт [SteamKit2](https://github.com/SteamRE/SteamKit)) и публичные страницы
  steamcommunity.com.
- Использование сторонних клиентов может противоречить
  [Соглашению подписчика Steam](https://store.steampowered.com/subscriber_agreement/).
  **Вы используете SteamChatX на свой страх и риск.** Авторы не несут ответственности за
  блокировку аккаунта, потерю данных или иные последствия использования приложения.
- Приложение распространяется «как есть», без каких-либо гарантий. См. раздел «Лицензия».
- Если вы представляете Valve Corporation и считаете, что проект нарушает ваши права —
  откройте issue в этом репозитории, мы оперативно отреагируем.

**English**

- SteamChatX is **not an official Steam application** and is **not affiliated with, endorsed by,
  or sponsored by Valve Corporation** in any way.
- **Steam**, the Steam logo and related marks are trademarks and/or registered trademarks of
  Valve Corporation in the U.S. and/or other countries. They are used here solely for
  descriptive purposes (nominative use) to identify the service this client connects to, and do
  not imply any affiliation or endorsement.
- This project contains **no** Valve source code, assets or proprietary material. It talks to
  Steam through the open-source [JavaSteam](https://github.com/Longi94/JavaSteam) library and
  public steamcommunity.com pages.
- Using third-party clients may violate the
  [Steam Subscriber Agreement](https://store.steampowered.com/subscriber_agreement/).
  **Use at your own risk.** The authors are not responsible for account restrictions, data loss
  or any other consequences.
- The software is provided "as is", without warranty of any kind. See "License" below.
- If you represent Valve Corporation and believe this project infringes your rights, please
  open an issue in this repository and we will respond promptly.

---

## Приватность

- Логин и пароль вводятся только в само приложение и передаются **напрямую на серверы Steam**
  через протокол Steam (JavaSteam). Никаких промежуточных серверов у проекта нет.
- Для восстановления сессии на устройстве хранится только refresh-токен Steam — в
  `EncryptedSharedPreferences`, зашифрованных ключом из Android Keystore. Пароль не сохраняется.
- Приложение не собирает аналитику и не отправляет данные третьим лицам. Telegram-компоненты
  аналитики/пушей в форке отключены на уровне манифеста.

---

## Что умеет

- Вход по логину/паролю с подтверждением Steam Guard в мобильном приложении Steam; сессия
  переживает перезапуск.
- Список друзей с аватарками, статусом онлайн и текущей игрой; личные чаты в реальном времени
  с историей с сервера.
- Групповые чаты Steam (каналы, история, отправка).
- Голосовые и видео-сообщения (кружки), фото/видео из Steam CDN с превью прямо в чате.
- Эмотиконы и стикеры Steam: рендер в тексте и пикер для отправки.
- Экран профиля: уровень и XP-прогресс, значки, анимированная рамка аватара, «О себе»,
  история ников, счётчики игр/скриншотов/групп, список игр.
- 1:1 голосовые звонки другу через WebRTC (экспериментально).
- Папки чатов, фильтры «Все / В сети / Группы», тёмная тема.

---

## Архитектура

```
org.steamchat.ui  (Kotlin, внутри TMessagesProj)   — экраны и ячейки на UI-примитивах Telegram
        │
steamchat-domain  (чистый Kotlin/JVM)               — модели, контракт SteamService, FakeSteamService
        ▲
steamchat-steamkit                                  — JavaSteamService: реальный бэкенд поверх JavaSteam
        │
   Steam CM servers / steamcommunity.com
```

UI-база — форк [Telegram for Android](https://github.com/DrKLO/Telegram) (только рендер,
темы, компоненты). MTProto и вся телеграмная сетевая часть не используются; собственные
экраны написаны с нуля против доменных моделей Steam. Через границу `SteamService` не проходит
ни одного типа JavaSteam.

---

## Сборка

Требования: Android Studio 2025.1.x, Android SDK 36, Android NDK 27.2.12479018, JDK 17+
(подходит JBR из состава Android Studio).

```bash
git clone --recursive --shallow-submodules https://github.com/Maramazzi/steamchat.git
cd steamchat

# Windows / Git Bash — JBR из Android Studio
export JAVA_HOME="/c/Program Files/Android/Android Studio/jbr"

# Быстрая проверка кода (~10 с)
./gradlew :TMessagesProj:compileDebugKotlin

# Юнит-тесты домена и адаптера
./gradlew :steamchat-domain:test :steamchat-steamkit:test

# Полный debug-APK (~2 мин при прогретом кэше нативной части)
./gradlew :TMessagesProj_App:assembleAfatDebug
```

APK: `TMessagesProj_App/build/outputs/apk/afat/debug/app.apk`

Установка на устройство/эмулятор:

```bash
adb install -r TMessagesProj_App/build/outputs/apk/afat/debug/app.apk
```

Для release-сборки замените `TMessagesProj/config/release.keystore` и `google-services.json`
на свои (в репозитории лежат заглушки, унаследованные от базового проекта).

---

## Лицензия и благодарности

- Код проекта распространяется под **GNU GPL v2** — той же лицензией, что и базовый
  [Telegram for Android](https://github.com/DrKLO/Telegram), форком которого является UI-слой.
  Полный текст — в файле [LICENSE](LICENSE).
- Telegram — товарный знак Telegram FZ-LLC; проект не связан с Telegram и не использует его
  сервисы.
- [JavaSteam](https://github.com/Longi94/JavaSteam) — JVM-реализация протокола Steam,
  распространяется под собственной лицензией (см. репозиторий).
- Иконки и темы интерфейса частично взяты из ресурсов Telegram for Android (GPL v2).

Steam и Valve — товарные знаки Valve Corporation. Все остальные товарные знаки принадлежат
их владельцам.
