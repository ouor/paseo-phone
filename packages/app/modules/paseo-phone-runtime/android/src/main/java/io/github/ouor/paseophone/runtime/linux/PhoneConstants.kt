package io.github.ouor.paseophone.runtime.linux

/** The one Kotlin source for these values (architecture §7.5). Shell scripts get them through env. */
object PhoneConstants {
  const val DAEMON_HOST = "127.0.0.1"
  const val DAEMON_PORT = 6767
  const val DAEMON_LISTEN = "$DAEMON_HOST:$DAEMON_PORT"
  const val SSH_PORT = 2222
  const val GUEST_PASEO_HOME = "/root/.paseo"
  const val GUEST_EXCHANGE = "/root/exchange"
  const val SCRIPT_DIR = "/opt/paseo-phone"

  // Must match variants.phone in packages/app/app.config.js.
  const val APPLICATION_ID = "io.github.ouor.paseophone"
  const val URL_SCHEME = "paseo-phone"

  const val ENV_DAEMON_VERSION = "PASEO_DAEMON_VERSION"
  const val ENV_NODE_VERSION = "NODE_VERSION"
  const val ENV_SSH_PORT = "PASEO_PHONE_SSH_PORT"
  const val ENV_DAEMON_LISTEN = "PASEO_PHONE_DAEMON_LISTEN"
  const val ENV_NODENAME = "PASEO_PHONE_NODENAME"
}
