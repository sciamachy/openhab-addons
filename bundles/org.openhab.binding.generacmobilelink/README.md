# Generac MobileLink Binding

This binding communicates with the Generac MobileLink API and reports on the status of Generac manufactured generators, including versions resold under the brands Eaton, Honeywell and Siemens.

## Supported Things

### MobileLink Account

A MobileLink account bridge thing represents a user's MobileLink account and is responsible for authentication and polling for updates.

ThingTypeUID: `account`

### Generator

A Generator thing represents an individual generator linked to an account bridge. Multiple generators are supported.

ThingTypeUID: `generator`

## Discovery

The MobileLink account bridge must be added manually. Once added, generator things will automatically be added to the inbox.

## Thing Configuration

### MobileLink Account

| Parameter       | Description                                                                                          |
|-----------------|------------------------------------------------------------------------------------------------------|
| username        | The email address used to log in to the MobileLink app                                               |
| password        | The password used to log in to the MobileLink app                                                    |
| mfaCode         | One-time code for accounts with multi-factor authentication, see below. Leave empty otherwise.       |
| refreshInterval | The frequency in seconds to poll for generator updates. Default is 300, the minimum is 30.           |

The binding logs in the same way as the MobileLink mobile app.
After the first login it keeps a refresh token in openHAB's storage and does not need to log in again, even after a restart, until MobileLink invalidates that token.
Changing the username discards the stored token; deleting the bridge in the UI deletes it.

If MobileLink refuses the login, for example because of a wrong password, the bridge goes `OFFLINE (CONFIGURATION_ERROR)` and stops trying, so that repeated attempts cannot get the account locked.
Correct the configuration and save it to try again.
Communication errors take the bridge offline after three failed polls in a row; it keeps trying, and logins that fail this way are spaced out from five minutes up to one hour.

Generac states that it restricts third-party access to MobileLink.
Keep the refresh interval moderate; generator status rarely changes faster than every few minutes.

### Multi-Factor Authentication

If your MobileLink account uses multi-factor authentication, the bridge goes `OFFLINE (CONFIGURATION_PENDING)` after accepting the password and tells you where to find the code:

- **Authenticator app:** enter the current code as `mfaCode` and save.
  You can also enter the code together with the email address and password when you first create the bridge, if you save within the code's validity period.
- **SMS or email:** the code is sent once the password has been accepted.
  Enter it as `mfaCode` and save within ten minutes.

If no code is entered within ten minutes, the bridge stops the login and says so; it does not start a new one (and send a new SMS or email) by itself.
Save the configuration to start a new login.

A code is only asked for when the binding has to log in again, not on every restart.
Each code is used only once, so leaving an old code in the configuration is harmless: the bridge asks for a new one when it needs it.
Push notifications and security keys cannot be used; switch the account to one of the factors above to use it with openHAB.

### Upgrading from Earlier Versions

MobileLink replaced its previous login page, which earlier versions of this binding used, so those versions can no longer log in.
The configuration parameters are unchanged: the existing `username` and `password` keep working.
Accounts with multi-factor authentication need one code on the first start, as described above.

### Generator

| Parameter   | Description                                                                  |
|-------------|------------------------------------------------------------------------------|
| generatorId | The ID of the generator, as found by discovery (the MobileLink apparatus ID) |

## Channels

### Generator Channels

All channels are read-only.

| Channel ID           | Item Type                   | Description                       |
|----------------------|-----------------------------|-----------------------------------|
| heroImageUrl         | String                      | Hero Image URL                    |
| statusLabel          | String                      | Status Label                      |
| statusText           | String                      | Status Text                       |
| activationDate       | DateTime                    | Activation Date                   |
| deviceSsid           | String                      | Device SSID                       |
| status               | Number                      | Status                            |
| isConnected          | Switch                      | Is Connected                      |
| isConnecting         | Switch                      | Is Connecting                     |
| showWarning          | Switch                      | Show Warning                      |
| hasMaintenanceAlert  | Switch                      | Has Maintenance Alert             |
| lastSeen             | DateTime                    | Last Seen                         |
| connectionTime       | DateTime                    | Connection Time                   |
| runHours             | Number:Time                 | Number of Hours Run               |
| batteryVoltage       | Number:ElectricPotential    | Battery Voltage                   |
| hoursOfProtection    | Number:Time                 | Number of Hours of Protection     |
| signalStrength       | Number:Dimensionless        | Signal Strength                   |

## Full Example

### Things

```java
Bridge generacmobilelink:account:main "MobileLink Account" [ username="foo@bar.com", password="secret", refreshInterval=300 ] {
    Thing generator 123456 "MobileLink Generator" [ generatorId="123456" ]
}
```

### Items

```java
String GeneratorHeroImageUrl "Hero Image URL [%s]" { channel="generacmobilelink:generator:main:123456:heroImageUrl" }
String GeneratorStatusLabel "Status Label [%s]" { channel="generacmobilelink:generator:main:123456:statusLabel" }
String GeneratorStatusText "Status Text [%s]" { channel="generacmobilelink:generator:main:123456:statusText" }
DateTime GeneratorActivationDate "Activation Date [%s]" { channel="generacmobilelink:generator:main:123456:activationDate" }
String GeneratorDeviceSsid "Device SSID [%s]" { channel="generacmobilelink:generator:main:123456:deviceSsid" }
Number GeneratorStatus "Status [%d]" { channel="generacmobilelink:generator:main:123456:status" }
Switch GeneratorIsConnected "Is Connected [%s]" { channel="generacmobilelink:generator:main:123456:isConnected" }
Switch GeneratorIsConnecting "Is Connecting [%s]" { channel="generacmobilelink:generator:main:123456:isConnecting" }
Switch GeneratorShowWarning "Show Warning [%s]" { channel="generacmobilelink:generator:main:123456:showWarning" }
Switch GeneratorHasMaintenanceAlert "Has Maintenance Alert [%s]" { channel="generacmobilelink:generator:main:123456:hasMaintenanceAlert" }
DateTime GeneratorLastSeen "Last Seen [%s]" { channel="generacmobilelink:generator:main:123456:lastSeen" }
DateTime GeneratorConnectionTime "Connection Time [%s]" { channel="generacmobilelink:generator:main:123456:connectionTime" }
Number:Time GeneratorRunHours "Number of Hours Run [%d]" { channel="generacmobilelink:generator:main:123456:runHours" }
Number:ElectricPotential GeneratorBatteryVoltage "Battery Voltage [%d]v" { channel="generacmobilelink:generator:main:123456:batteryVoltage" }
Number:Time GeneratorHoursOfProtection "Number of Hours of Protection [%d]" { channel="generacmobilelink:generator:main:123456:hoursOfProtection" }
Number:Dimensionless GeneratorSignalStrength "Signal Strength [%d]" { channel="generacmobilelink:generator:main:123456:signalStrength" }

```

### Sitemap

```perl
sitemap generacmobilelink label="Generac MobileLink"
{
    Frame label="Generator Status" {
        Text item=GeneratorStatus
        Text item=GeneratorStatusLabel
        Text item=GeneratorStatusText
    }

    Frame label="Generator Properties" {
        Text item=GeneratorRunHours
        Text item=GeneratorHoursOfProtection
        Text item=GeneratorBatteryVoltage
        Text item=GeneratorSignalStrength
    }
}
```
