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

| Parameter       | Description                                                                 |
|-----------------|-----------------------------------------------------------------------------|
| sessionCookie   | Session cookie obtained from browser after logging in to MobileLink portal  |
| refreshInterval | The frequency to poll for generator updates, minimum duration is 30 seconds |

### Generator

| Parameter   | Description                                                              |
|-------------|--------------------------------------------------------------------------|
| generatorId | The apparatus ID of the generator (automatically set during discovery)   |

### Obtaining the Session Cookie

The MobileLink portal uses CAPTCHA protection on its login page, which prevents automated login.
You must manually obtain a session cookie from your browser:

1. Open your web browser and navigate to [app.mobilelinkgen.com](https://app.mobilelinkgen.com)
2. Log in with your MobileLink credentials
3. Open browser Developer Tools (F12 or right-click → Inspect)
4. Go to the **Application** tab (Chrome/Edge) or **Storage** tab (Firefox)
5. In the left sidebar, expand **Cookies** and select `https://app.mobilelinkgen.com`
6. Copy **all** cookie name=value pairs (not just `.AspNetCore.Cookies`) and paste them into the `sessionCookie` parameter as a single semicolon-separated string

### Cookie Auto-Renewal

The binding automatically captures `Set-Cookie` headers from API responses and merges them into the active session cookie, mimicking how a browser maintains a session.
Updated cookies are persisted to OpenHAB's storage service so they survive restarts.

This means the initial cookie you provide should stay valid indefinitely as long as the server keeps renewing it.
If the session does expire (HTTP 401/403), the binding will go OFFLINE with a "session expired" message and you will need to repeat the manual cookie extraction.

## Channels

### Account Channels

| Channel ID     | Type    | Description                                                        |
|----------------|---------|--------------------------------------------------------------------|
| cookieUpdated  | Trigger | Fires when the session cookie is auto-renewed from a server response |

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
Bridge generacmobilelink:account:main "MobileLink Account" [ sessionCookie="CfDJ8...<long cookie value>...", refreshInterval=60 ] {
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

### Rules (JS Automation)

React to cookie auto-renewal events:

```javascript
rules
  .when()
    .channel('generacmobilelink:account:main:cookieUpdated').triggered()
  .then(e => {
    console.info('Generac session cookie was auto-renewed');
  })
  .build('Generac Cookie Renewed');
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
