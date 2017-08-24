FROM anapsix/alpine-java:jre8

# Add enilink platform product
ADD target-maven/products/net.enilink.platform.product/linux/gtk/x86_64 /opt/enilink-platform
# make it executable
RUN chmod +x "/opt/enilink-platform/enilink-platform"

# Fix DNS resolution issues when nss is not installed
# see https://github.com/dockmob/docker-java/blob/master/Dockerfile
RUN echo 'hosts: files mdns4_minimal [NOTFOUND=return] dns mdns4' >> /etc/nsswitch.conf

# Run the enilink platform server
ENTRYPOINT ["/opt/enilink-platform/enilink-platform"]
