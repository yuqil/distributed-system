all: FileData.class RemoteFile.class Server.class ProxyCache.class Proxy.class

%.class: %.java
	javac $<

clean:
	rm -f *.class
