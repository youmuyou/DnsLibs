# MacOS DNS proxy sample application

## Howto build

### Xcode GUI

    Open Xcode
    Load project
    Press Build (Command+B)

### Terminal

Run the following sequence in terminal

    cd <dns-libs-dir>/netext-testapp
    xcodebuild

## Howto run

### Start VPN service

#### Xcode GUI

    Press Command+R

#### System preferences when vpn is already running

    Go to system network preferences
    There should be `Adguard VPN` service
    Click on it and press `Connect` button

### Set DNS proxy as system DNS server

    Go to system network preferences
    Click on the connection you want to by filtered
    Go to advanced/DNS servers
    Add there the tunnel address: 198.18.0.1 (for now hardcoded in `PacketTunnelProvider.m`)
