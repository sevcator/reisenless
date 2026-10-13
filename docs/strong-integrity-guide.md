# Руководство: Достижение MEETS_STRONG_INTEGRITY в Reisenless / Udonge

## 1. Что такое MEETS_STRONG_INTEGRITY?

В Google Play Integrity API существует три уровня целостности:
1. **MEETS_BASIC_INTEGRITY**: Базовая целостность (рут скрыт, базовое окружение).
2. **MEETS_DEVICE_INTEGRITY**: Устройство соответствует сертифицированному CTS-профилю. Требуется для 99.9% банковских приложений (Сбербанк, Тинькофф, Mir Pay, Google Wallet, Альфа-Банк).
3. **MEETS_STRONG_INTEGRITY**: Наивысший уровень безопасности, требующий **аппаратной Key Attestation** в TEE (TrustZone) или StrongBox с заблокированным загрузчиком и подлинным сертификатом производителя, уходящим корнями в Google Hardware Attestation Root CA.

---

## 2. Почему обычные/публичные Keybox дают только DEVICE_INTEGRITY?

Google использует два механизма фильтрации:
- **CRL (Certificate Revocation List)**: Полный отзыв сертификата. Отозванный сертификат не даёт даже `DEVICE_INTEGRITY`.
- **Server-side Softban (Мягкий бан на стороне Google)**:
  - Google непрерывно сканирует репозитории GitHub, каналы Telegram и форумы на предмет утечек `keybox.xml`.
  - Когда обнаружен слитый серийный номер, Google не отзывает его полностью (чтобы не сломать устройства тысячам пользователей), а **помещает в базу Softban**.
  - Для всех ключей из этой базы Google на сервере **принудительно ограничивает вердикт уровнем `MEETS_DEVICE_INTEGRITY`** и блокирует выдачу `MEETS_STRONG_INTEGRITY`.

> [!IMPORTANT]
> Ни один публичный Keybox из открытых источников в интернете не сможет выдать `MEETS_STRONG_INTEGRITY`, потому что Google уже добавил его серийный номер в базу Softban.
> Для `MEETS_STRONG_INTEGRITY` требуется **приватный (не засвеченный в сети) Golden Keybox**.

---

## 3. Что уже настроено в Reisenless + Udonge?

В Reisenless и Udonge уже встроена полная архитектура для генерации аппаратных сертификатов:
1. **TEESimulator (`libTEESimulator.so` + `classes.dex`)**:
   - Перехватывает обращения к `keystore2` на Android 12+.
   - Формирует синтетический ASN.1 `KeyDescription`:
     - `attestationSecurityLevel = TrustedEnvironment`
     - `keymasterSecurityLevel = TrustedEnvironment`
     - `verifiedBootState = 0` (Verified / green)
     - `deviceLocked = true` (1)
     - `verifiedBootHash` (из `ro.boot.vbmeta.digest` в `boot_hash.bin`)
2. **Подлинный OEM `verifiedBootKey` Xiaomi Mi 10T Pro**:
   - Извлечён из заводского `target_rom/firmware-update/vbmeta.img`:
     SHA-256 хэш открытого ключа OEM: `c5d3c71bc70d58e3e0409ca9d9b34c0dbac1d2f09a5de948a4b8f090f1926965`.
   - Прошит в `udonge/payload/defaults/boot_key.bin`.
3. **Автоматический охотник (Keybox Hunter)**:
   - Демон `hunt_daemon` в фоновом режиме умеет автоматически тестировать кандидаты через Play Integrity Checker (`gr.nikolasspyr.integritycheck`).

---

## 4. Пошаговая инструкция: как применить свой Golden Keybox

Если у вас есть собственный приватный Keybox (дамп с донорского устройства или приватный ключ):

### Шаг 1: Скопируйте файл на телефон
Поместите файл в корень памяти телефона:
```sh
/sdcard/keybox.xml
```

### Шаг 2: Выполните команду импорта через терминал (Termux или ADB Shell)
```sh
su -c "sh /data/.ms/udonge/runtime/keybox_heal.sh import_strong /sdcard/keybox.xml"
```
*(где `/data/.ms/` — путь к вашей приватной директории Reisenless)*.

### Шаг 3: Что произойдёт автоматически:
1. Скрипт проверит валидность XML и отсутствие отозванных сертификатов в CRL.
2. Скопирует ключ в рабочую директорию Udonge.
3. Перезапустит `TEESimulator` и сбросит кэш DroidGuard (`/data/data/com.google.android.gms/databases/dg.db*`).
4. Автоматически запустит Play Integrity Checker и протестирует вердикт на серверах Google.
5. При успехе:
   - Зафиксирует `MEETS_STRONG_INTEGRITY`.
   - Создаст файл блокировки `.strong_locked`.
   - Сохранит резервную копию в `/sdcard/GOLDEN_KEYBOX.xml`.

---

## 5. Проверка статуса

В любой момент вы можете проверить текущий статус ключа и уровень вердиктов:
```sh
su -c "sh /data/.ms/udonge/runtime/keybox_heal.sh status"
```
Команда покажет:
- DeviceID текущего ключа.
- Статус сертификатов в CRL.
- Заблокирован ли статус Strong (`Strong Locked : [✔ YES]`).
