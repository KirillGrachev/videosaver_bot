Saver Bot: Minecraft launcher plugin
====================================

This jar is NOT part of the bot runtime. It is an optional Bukkit / Spigot /
Paper plugin that starts the bot as a child process of your Minecraft server
and supervises it.

Install
-------
1. Copy saver-bot-launcher-plugin.jar into the server's plugins/ folder.
2. Create a bot folder next to plugins/, for example "saver-bot", and copy
   the distribution into it: saver-launcher.jar, saver-bot.jar, libs/,
   libs-manifest.txt, plus your config/application.yml and config/.env.
   Do not copy this minecraft/ folder or start.bat / start.sh into the bot
   folder: they are for a manual start only.
3. Start the server. On the first run the plugin writes
   plugins/SaverBotLauncher/config.yml - set bot-home there if your bot
   folder is named differently.

Config keys (plugins/SaverBotLauncher/config.yml)
-------------------------------------------------
bot-home              bot distribution folder, relative to the server root
                      (the folder that contains plugins/) or absolute;
                      default: saver-bot
launcher-jar          launcher jar name inside bot-home;
                      default: saver-launcher.jar
java-binary           empty means the java that runs this server; when that
                      java is older than 21 the launcher downloads its own
                      JRE 21, as it always does
start-on-enable       start the bot together with the server
restart-on-crash      restart the child process after a non-zero exit code
stop-timeout-seconds  grace period for a clean shutdown before the kill

Commands
--------
/saverbot status | start | stop | restart
Permission: saverbotlauncher.admin (default: op).

Notes
-----
Requirements: Minecraft 1.20+ (JVM 17+), Spigot, Paper or Purpur.
The bot console is piped into the server log with the [bot] prefix.
The periodic [status] heartbeat of the bot stays below the console: it is
recorded at the debug level and appears in the server log only when a platform
changes state; /saverbot status still shows the latest line and its age.
On JVM 22+ the child process is started with --enable-native-access=ALL-UNNAMED
(and on 23+ also --sun-misc-unsafe-memory-access=allow), so the JDK's own startup
warnings about the sqlite native library and Guava never reach the server log.
Crash restarts are budgeted: 5 attempts per 10 minutes, backoff 5 s to 60 s.
When the budget is spent the plugin logs it and waits for a human.
No bot code lives in this plugin, so the very same distribution keeps working
from start.bat, start.sh or a service manager without the Minecraft server.
