FROM openjdk:7
ENV BUILD_NUM 916
RUN wget https://download.jitsi.org/jitsi-videobridge/linux/jitsi-videobridge-linux-x64-${BUILD_NUM}.zip \
 && unzip jitsi-videobridge-linux-x64-${BUILD_NUM}.zip \
 && mv jitsi-videobridge-linux-x64-${BUILD_NUM} jvb \
 && rm jitsi-videobridge-linux-x64-${BUILD_NUM}.zip \
 && mkdir /root/.sip-communicator
COPY entrypoint.sh /entrypoint.sh
CMD /entrypoint.sh
