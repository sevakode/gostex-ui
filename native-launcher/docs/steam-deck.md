# GOSTEX на Steam Deck / Linux

Установка рассчитана на Linux app-image из GitHub Release: ZIP с каталогом `GOSTEX/`, нативным `bin/GOSTEX` и встроенным `lib/runtime`. Системная Java, Proton и изменение read-only раздела SteamOS не нужны. Нужен Python 3.9+ со стандартной библиотекой. Установщик пишет только файлы текущего пользователя, без `sudo`.

Наличие кода установщика и host-тестов не означает проверку на настоящем Deck. До подключения устройства остаются открыты Gaming Mode, Steam Input, фокус внешних окон, сон/пробуждение и расход памяти/CPU. Zona и SmartTube для Linux здесь не устанавливаются; их совместимость не подтверждена.

## Первая установка

1. На Deck откройте Power → Switch to Desktop. Загрузите Linux x86_64 ZIP, `gostex-install.py`, `bootstrap.py` и `gostex.svg` из одного проверенного GOSTEX Release в одну папку. Для приватного репозитория потребуется обычный вход в GitHub через браузер. Не сохраняйте токен GitHub в командах или настройках приложения.
2. Возьмите `versionCode`, `versionName` и SHA-256 именно Linux ZIP из опубликованного проверенного release manifest. SHA-256 относится к ZIP целиком. Замените значения в команде ниже; файл `bootstrap.py` должен лежать рядом с установщиком.
3. В Konsole выполните:

```bash
cd ~/Downloads
python3 gostex-install.py \
  --archive "$HOME/Downloads/gostex-linux-x86_64.zip" \
  --version-code VERSION_CODE \
  --version-name VERSION_NAME \
  --sha256 SHA256_FROM_VERIFIED_MANIFEST
```

Имена ZIP в конкретном релизе могут отличаться. Для пути с пробелами сохраняйте кавычки. Установщик сверяет хеш и структуру до активации версии. Внутренние установочные скрипты ZIP не исполняются. Linux ZIP нельзя заменить macOS app image или Android APK.

После установки:

| Назначение | Путь по умолчанию |
| --- | --- |
| Постоянный запуск | `~/.local/bin/gostex-launch` |
| Меню приложений | `~/.local/share/applications/gostex.desktop` |
| Версии приложения | `~/.local/share/gostex/releases/<versionCode>/GOSTEX/` |
| Текущая/предыдущая версия | `~/.local/share/gostex/current`, `previous` |
| Установленный bootstrap | `~/.local/share/gostex/bootstrap.py` |
| Скачанные обновления | `~/.local/share/gostex/updates/<versionCode>.zip` |

Абсолютный `XDG_DATA_HOME`, если он установлен, заменяет `~/.local/share` для данных/desktop entry/icon. Постоянный launcher остаётся в `~/.local/bin`. Настройки библиотеки остаются в отдельном существующем desktop-хранилище; смена app image их не заменяет.

## Добавление в Gaming Mode / Big Picture

Steam официально поддерживает добавление установленных приложений через **Games → Add a Non-Steam Game to My Library**. В Desktop Mode откройте это меню, выберите **GOSTEX**, затем **Add Selected Programs**. Если записи нет в списке, через **Browse** выберите `/home/deck/.local/bin/gostex-launch` — подставьте своё имя домашней папки, если оно другое. [Steam Support: добавление Non-Steam игр](https://help.steampowered.com/en/faqs/view/4B8B-9697-2338-40EC), [Steam Support: Desktop FAQ](https://help.steampowered.com/en/faqs/view/671A-4453-E8D2-323C/).

В свойствах добавленной записи проверьте:

| Поле | Значение |
| --- | --- |
| Name | `GOSTEX` |
| Target | `"/home/deck/.local/bin/gostex-launch"` |
| Start In | `"/home/deck"` |
| Launch Options | `--gaming-mode` |
| Compatibility | Принудительный Proton выключен: это нативное Linux-приложение |

Используйте постоянный launcher, чтобы обновление не ломало ярлык. Установщик создаёт `gostex.desktop` с `Name=GOSTEX`, `StartupWMClass=GOSTEX` и исходным логотипом, но не переписывает `shortcuts.vdf` и чужие записи Steam.

Откройте настройки контроллера именно этой Non-Steam записи. Текущий прототип принимает клавиатурные события через Steam Input:

| Кнопка Deck | Назначение |
| --- | --- |
| D-pad | Клавиши ← ↑ → ↓ |
| A | Enter |
| B | Escape |
| Y | F1 |
| Правый трекпад | Мышь, щелчок — левая кнопка (для диалогов и ввода) |

Сохраните выбранный профиль контроллера в Steam. Затем вернитесь через **Return to Gaming Mode**, откройте **Library → Non-Steam → GOSTEX**. Это путь установки и настройки; реальное прохождение перечисленных шагов на устройстве должно быть записано отдельно. Общая информация об индивидуальных настройках контроллера: [Steam Deck Basic Use](https://help.steampowered.com/en/faqs/view/69E3-14AF-9764-4C28/).

## Обновление и возврат версии

Desktop updater проверяет подписанные метаданные и SHA-256, сохраняет ZIP в собственном `updates/<versionCode>.zip`, затем атомарно записывает `pending.properties`. Пока приложение запущено, его каталог не заменяется. При следующем запуске bootstrap ещё раз проверяет хеш, путь и структуру ZIP, распаковывает во временный каталог, затем атомарно переключает `current`. `previous` сохраняет предыдущую версию; остальные установленные версии автоматически не удаляются.

Протокол `pending.properties` — UTF-8 Java Properties с ключами `versionCode`, `versionName`, `path` и `sha256`. `path` должен указывать на абсолютный собственный `updates/<versionCode>.zip`. Bootstrap поддерживает Java escapes и continuation; ZIP ограничен 1 ГиБ распакованных данных и 20 000 записей, запрещены traversal, absolute paths, ссылки, special files, повторы и чужие корневые каталоги. ZIP64 для этого ограниченного формата не используется. Имена `bootstrap.py`, `gostex-install.py`, `gostex.svg` в корне ZIP допустимы как дополнительные исходные assets; при обновлении они пропускаются. Установленный bootstrap обновляется только повторным явным запуском проверенного установщика.

Если проверка обновления не прошла, `pending.properties` остаётся для диагностики, а текущая установленная версия запускается. Перед исполнением bootstrap задаёт `GOSTEX_EXECUTABLE` точным нативным путём; дочерние тестовые окна используют ту же установленную версию. Все дополнительные аргументы передаются отдельными argv без shell.

Чтобы вручную вернуться к предыдущей версии, закройте GOSTEX и запустите в Konsole:

```bash
~/.local/bin/gostex-launch --rollback
```

При откате текущая версия становится `previous`, поэтому командой можно вернуться обратно. Pending-файл переименовывается в `pending.before-rollback.properties`, чтобы следующая загрузка сразу не отменила выбор владельца. Ошибка самого `exec` (например, отсутствующий launcher/loader) также вызывает попытку предыдущей версии. Ошибка внутри уже запущенной JVM требует ручного `--rollback`; bootstrap не оценивает поведение приложения после `exec`.

## SSH для совместной проверки

SSH не нужен для обычной установки. Для удалённой проверки откройте Konsole на Deck. Если пароль пользователя ещё не задан, задайте его **на устройстве** командой `passwd`. Пароль вводится в локальном терминале и не передаётся в чат.

```bash
hostname -I
sudo systemctl start sshd
```

Вторую команду выполните локально; `sudo` здесь относится только к запуску штатного SSH-сервиса, установщик GOSTEX его не вызывает. `start` не включает постоянный автозапуск. Сообщите разработчику локальный IP из `hostname -I` и имя пользователя; пароль, ключи и приватные профили не отправляйте. Предпочтителен доступ через согласованный SSH public key. После работы сервис можно остановить локально:

```bash
sudo systemctl stop sshd
```

Сервис называется `sshd.service`; команды соответствуют [ArchWiki OpenSSH](https://wiki.archlinux.org/title/OpenSSH) и [systemctl manual](https://man.archlinux.org/man/systemctl.1). Если сервис отсутствует или не запускается, сохраните текст ошибки для проверки; не отключайте read-only SteamOS и не устанавливайте системные пакеты по предположению.

## Что проверить на Deck

- Запуск из Gaming Mode именно через постоянную Non-Steam запись без Proton; правильные логотип и полноэкранное отображение.
- D-pad/A/B/Y, правая панель, ввод имени/путей, сохранение настроек после выхода.
- Две тестовые карточки: запуск отдельного окна → закрытие → возврат к исходной карточке; затем настоящая игра/приложение с его платформенным lifecycle.
- Обновление на следующем запуске, сохранение библиотеки/профиля Steam Input и `--rollback`.
- Сон/пробуждение, Wi-Fi offline, выход через Steam, отсутствие фонового busy loop и замеры RSS/CPU.

Результаты фиксируются с версией SteamOS/моделью Deck/версией GOSTEX. Host-тесты поддельных app images проверяют installer/bootstrap и не заменяют эти проверки.
