##
#   Prints a list of all internally predefined alarms
#
list_alarms()
{
    CLASSPATH="$(getProperty dcache.paths.classpath)" quickJava org.dcache.alarms.shell.ListPredefinedTypes
}

##
#   Invokes the main method of SendAlarm with the given arguments
#
send_alarm() # $@ = [-s=<source-uri>] [-l=<log level>] [-t=<alarm subtype>] message
{
    local host
    local port

    host=$(getProperty dcache.log.server.host)
    port=$(getProperty dcache.log.server.port)

    CLASSPATH="$(getProperty dcache.paths.classpath)" quickJava org.dcache.alarms.shell.SendAlarm -d="dst://${host}:${port}" "$@"
}

##
#   Invokes the main method of AlarmDefinitionManager with the given arguments
#
handle_alarm_definition() # $1 = [add, modify, remove]
{
    local file
    file=$(getProperty alarms.definitions.path)
    CLASSPATH="$(getProperty dcache.paths.classpath)" quickJava org.dcache.alarms.shell.AlarmDefinitionManager $1 ${file}
}
