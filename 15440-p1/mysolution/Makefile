all:  server mylib.so 

server: server.o
	gcc -Wall -Werror server.o -o server  -L../lib -ldirtree

server.o: server.c
	gcc -Wall -Werror -I../include  -c server.c 

RPClib.o: RPClib.c 
	gcc -Wall -Werror RPClib.c -o RPClib.o

mylib.o: mylib.c
	gcc -Wall  -I../include -fPIC -DPIC -c mylib.c RPClib.c 

mylib.so: mylib.o
	ld -shared -L../lib -o mylib.so mylib.o RPClib.o -ldl

clean:
	rm -f *.o *.so server
