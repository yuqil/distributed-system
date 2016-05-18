/*
 * Function prototype for RPClib
 * 
 * Author : Yuqi Liu< yuqil@andrew.cmu.edu>
 */

// first argument: function name
// second argument: the pointer to original close function(used for network socket)

#include <stdio.h>
#include <stdlib.h>
#include <arpa/inet.h>
#include <sys/types.h>
#include <sys/stat.h>
#include <netinet/in.h>
#include <sys/socket.h>
#include <string.h>
#include <unistd.h>
#include <err.h>
#include "RPClib.h"
#define MAXMSGLEN 100

// connect to server at given ip address and port
int connect2server(char *serverip, int port) {
	int sockfd, rv;
	struct sockaddr_in srv;

	// Create socket
	sockfd = socket(AF_INET, SOCK_STREAM, 0); // TCP/IP socket
	if (sockfd<0) {
		err(1, 0);			      // in case of error
		return -1;
	}
	
	// setup address structure to point to server
	memset(&srv, 0, sizeof(srv));			// clear it first
	srv.sin_family = AF_INET;			    // IP family
	srv.sin_addr.s_addr = inet_addr(serverip);	// IP address of server
	srv.sin_port = htons(port);			// server port

	// actually connect to the server
	rv = connect(sockfd, (struct sockaddr*)&srv, sizeof(struct sockaddr));
	if (rv<0) {
		err(1, 0);
		return -1;
	}

	return sockfd;
}


// use for log all operation name
int sendLog(char *function, int (*orig_close)(int fd)) {
	char *serverip;
	char *serverport;
	unsigned short port;
	char *msg=function;
	int sockfd, rv;
	struct sockaddr_in srv;
	
	// Get environment variable indicating the ip address of the server
	serverip = getenv("server15440");
	if (serverip) {}
	else {
		serverip = "127.0.0.1";
	}
	
	// Get environment variable indicating the port of the server
	serverport = getenv("serverport15440");
	if (serverport) {}
	else {
		serverport = "15440";
	}
	port = (unsigned short)atoi(serverport);
	
	// Create socket
	sockfd = socket(AF_INET, SOCK_STREAM, 0); // TCP/IP socket
	if (sockfd<0) err(1, 0);			      // in case of error
	
	// setup address structure to point to server
	memset(&srv, 0, sizeof(srv));			// clear it first
	srv.sin_family = AF_INET;			    // IP family
	srv.sin_addr.s_addr = inet_addr(serverip);	// IP address of server
	srv.sin_port = htons(port);			// server port

	// actually connect to the server
	rv = connect(sockfd, (struct sockaddr*)&srv, sizeof(struct sockaddr));
	if (rv<0) {
		err(1,0);
		return -1;
	}
	
	// send message to server
	int flag = send(sockfd, msg, strlen(msg), 0);	    // send message; should check return value
	
	// close socket
	orig_close(sockfd);

	return flag;
}