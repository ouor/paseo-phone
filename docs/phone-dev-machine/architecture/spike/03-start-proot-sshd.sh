#!/data/data/com.termux/files/usr/bin/bash
# Run in Termux. Starts OpenSSH sshd inside proot Debian on port 8023 (apps cannot bind <1024).
# Key: copies Termux's ~/.ssh/authorized_keys into Debian /root/.ssh (test key only).
proot-distro login debian -- bash -c '
  mkdir -p /root/.ssh /run/sshd && chmod 700 /root/.ssh
  cp /data/data/com.termux/files/home/.ssh/authorized_keys /root/.ssh/authorized_keys && chmod 600 /root/.ssh/authorized_keys
  ssh-keygen -A >/dev/null
  printf "Port 8023\nPasswordAuthentication no\nPermitRootLogin prohibit-password\n" > /etc/ssh/sshd_config.d/phone.conf'
setsid nohup proot-distro login debian -- /usr/sbin/sshd -D -e > "$HOME/proot-sshd.out" 2>&1 < /dev/null &
echo "started pid $!"
