/*
 * Simple server for 15440 project 1.3 for remote RPC file system
 * 
 * Author : Yuqi Liu< yuqil@andrew.cmu.edu>
 */
#include <stdio.h>
#include <stdlib.h>
#include <arpa/inet.h>
#include <sys/types.h>
#include <sys/stat.h>
#include <netinet/in.h>
#include <fcntl.h>
#include <dlfcn.h>
#include <dirent.h>
#include <sys/socket.h>
#include <string.h>
#include <unistd.h>
#include <err.h>
#include <errno.h>
#include <pthread.h>
#include <signal.h>
#include <sys/wait.h>
#include "../include/dirtree.h"
#include "RPClib.h"

#define PORT_CHANGE      // use for local test port change
#define DEBUG            // use for debug mode
#define MAXMSGLEN 512    
typedef struct dirtreenode dirtreenode;

// Following lines define function to process RPC call
void processRPC (void* buffer, int sessfd);
void process_open (open_request_header_t * open_header, int sessfd);   
void process_close(close_request_header_t * close_header, int sessfd);  
void process_write(write_request_header_t* wirte_header, int sessfd);   
void process_dirtree(dirtree_request_header_t* dirtree_header, int sessfd);
void process_read(read_request_header_t * read_header, int sessfd);
void process_unlink(unlink_request_header_t* unlink_header, int sessfd);
void process_lseek(lseek_request_header_t* lseek_header, int sessfd);
void process_stat(stat_request_header_t * stat_header, int sessfd);
void process_getEntry(getdirentries_request_header_t* getEntry_header,int sessfd);
void marshalTree(dirtreenode* dirHead, void* buf);
int get_total_len(dirtreenode* dirHead);  // get total memory needed to store tree

// sigchhild handler for reaping process
void sigchld_handler(int sig);
// client server routine
void serve_client(int connection);

int main(int argc, char**argv) {
	char *serverport;
	unsigned short port;
	int sockfd, rv;
	struct sockaddr_in srv, cli;
	socklen_t sa_size;
	int connection = 0;

	// Get environment variable indicating the port of the server
	serverport = getenv("serverport15440");
	if (serverport) port = (unsigned short)atoi(serverport);
	else {
		port=15440;
		#ifdef PORT_CHANGE
			port = 14743;
		#endif
	}
	
	// Create socket
	sockfd = socket(AF_INET, SOCK_STREAM, 0);	// TCP/IP socket
	if (sockfd<0) err(1, 0);			   // in case of error
	
	// setup address structure to indicate server port
	memset(&srv, 0, sizeof(srv));			// clear it first
	srv.sin_family = AF_INET;			// IP family
	srv.sin_addr.s_addr = htonl(INADDR_ANY);	// don't care IP address
	srv.sin_port = htons(port);			// server port

	// bind to our port
	rv = bind(sockfd, (struct sockaddr*)&srv, sizeof(struct sockaddr));
	if (rv<0) {
		err(1,0);
		return -1;
	}
	
	// start listening for connections
	rv = listen(sockfd, 5);
	if (rv<0) {
		err(1,0);
		return -1;
	}
	sa_size = sizeof(struct sockaddr_in);

	// sigchild handler
	signal(SIGCHLD, sigchld_handler);

	// main server loop, never quits
	while (1) {
		// wait for next client, get session socket
		connection = accept(sockfd, (struct sockaddr *)&cli, &sa_size);
		// fork another process to deal with client
		if (fork() == 0) {
			close(sockfd);
			serve_client(connection);
			close(connection);
			exit(0);
		}
		// parent close connection
		close(connection);
	}

	// close socket
	close(sockfd);
	return 0;
}


//  serve client routine
void serve_client(int connection) {
	char buf[MAXMSGLEN];
	int sessfd = connection;
	
	while(1) {
		int index = 0;         // loop count
		int read_size = 0;     // size have been read
		int length = 0;        // total length of data
		void *buffer = NULL;   // data buffer
		int rv;

		// get RPC call data
		while ((rv=recv(sessfd, buf, MAXMSGLEN, 0)) > 0) {
		// first time enter loop, create buffer for the whole request
			if (index == 0) {
				request_header_t* header = (request_header_t *) buf;
				length = header->total_len;    // get length
				buffer = malloc(length);
				memcpy(buffer, buf, rv);
				index ++;
				read_size += rv;
				// if received all data, begin process
				if (read_size == length) break;
			} else {
				memcpy(buffer + read_size, buf, rv);
				read_size += rv;
				// if received all data, begin process
				if (read_size == length) break;
			}
		}

		// either client closed connection, or error
		if (rv<0) {
			err(1,0);
			break;
		}

		// process RPC
		processRPC(buffer, sessfd);

		// free memory, close session
		free(buffer);
	}
}



// process RPC
// parameter buffer: holds all headers and data
// sessfd: client connection
void processRPC(void* buffer, int sessfd) {
	request_header_t *header = (request_header_t*) buffer;
	int opcode = header->opcode;   // get operation code
	#ifdef DEBUG
		fprintf(stderr, "OPCODE %d \n", opcode);
	#endif

	// process RPC
	switch (opcode) {
		case OPEN:
			process_open((open_request_header_t *)header->data, sessfd);
			break;
		case CLOSE:
			process_close((close_request_header_t *)header->data, sessfd);
			break;
		case WRITE:
			process_write((write_request_header_t *)header->data, sessfd);
			break;
		case DIRTREE:
			process_dirtree((dirtree_request_header_t *)header->data, sessfd);
			break;
		case READ:
			process_read((read_request_header_t *)header->data, sessfd);
			break;
		case UNLINK:
			process_unlink((unlink_request_header_t *)header->data, sessfd);
			break;
		case LSEEK:
			process_lseek((lseek_request_header_t *)header->data, sessfd);
			break;
		case STAT:
			process_stat((stat_request_header_t *)header->data, sessfd);
			break;			
		case GETENTRY:
			process_getEntry((getdirentries_request_header_t *)header->data, sessfd);
			break;
		default:
			fprintf(stderr, "Wrong opcode!\n");
	}
}


// process_getEntry RPC call
// parameter: getEntry header
// sessfd: client connection
void process_getEntry(getdirentries_request_header_t * getEntry_header, int sessfd) {
	// parameter
	int fd = getEntry_header->fd - FD_OFFSET;
	size_t nbytes = getEntry_header->nbyte;
	off_t *basep = malloc(sizeof(off_t));
	*basep = getEntry_header->basep;
	char* buf = malloc(nbytes);

	#ifdef DEBUG
		fprintf(stderr, "Server getentry going to call data! %d %p %p\n", fd, buf, basep);
	#endif

	int read_num = getdirentries(fd, buf, nbytes, basep);
	#ifdef DEBUG
		fprintf(stderr, "Server getentry to send read number! %d \n", read_num);
	#endif
	char *buffer = NULL;
	int length = 0;

	if (read_num < 0) {
		read_num = -1 * errno;
		length = sizeof(request_header_t) + sizeof(getdirentries_reply_header_t);
		buffer = malloc(length);
		request_header_t *header = (request_header_t *)buffer;
		header->opcode = GETENTRY;
		header->total_len = length;
		getdirentries_reply_header_t* reply_header = (getdirentries_reply_header_t *)header->data;
		reply_header->read_num = read_num;
	} else {
		length = sizeof(request_header_t) + sizeof(getdirentries_reply_header_t) + read_num;
		buffer = malloc(length);
		request_header_t *header = (request_header_t *)buffer;
		header->opcode = GETENTRY;
		header->total_len = length;
		getdirentries_reply_header_t* reply_header = (getdirentries_reply_header_t *)header->data;
		reply_header->read_num = read_num;
		reply_header->basep = *basep;
		memcpy(reply_header->data, buf, read_num);		
	}

	// send back result
	free(buf);
	int k = -1;
	while (k <= 0){
		k = send(sessfd, buffer, length, 0);	    // send message; should check return value
	}
	free(basep);
	free(buffer);
}


// process stat RPC
// parameter: stat header
// sessfd: client connection
void process_stat(stat_request_header_t * stat_header, int sessfd) {
	// get parameter
	int ver = stat_header->ver;
	int filename_len = stat_header->filename_len;
	char* path = malloc(filename_len);
	memcpy(path, stat_header->data, filename_len);

	// marshal data
	int length = sizeof(request_header_t) + sizeof(stat_reply_header_t) + sizeof(struct stat);
	char * buffer = malloc(length);
	request_header_t *header = (request_header_t *)buffer;
	header->opcode = STAT;
	header->total_len = length;
	stat_reply_header_t* reply_header = (stat_reply_header_t *)header->data;

	int state = __xstat(ver, path, (struct stat * )reply_header->data);
	if (state < 0) {
		state = -1 * errno;
	} 
	reply_header->state = state;

	#ifdef DEBUG
		fprintf(stderr, "Server stat going to send STAT data! %d \n", state);
	#endif
	// send to client stat result
	send(sessfd, buffer, length, 0);
	free(path);
	free(buffer);
}


// process lseek RPC
// parameter: lseek header
// sessfd: client connection
void process_lseek(lseek_request_header_t * lseek_header, int sessfd) {
	// get parameter
	int fd = lseek_header->fd - FD_OFFSET;
	off_t offset = lseek_header->offset;
	int whence = lseek_header->whence;
	int lseek_state = lseek(fd, offset, whence);
	if (lseek_state < 0) {
		lseek_state = -1 * errno;
	} 

	char msg[20];
	sprintf(msg, "%d", lseek_state);
	#ifdef DEBUG
		fprintf(stderr, "Server going to send LSEEK data! %s \n", msg);
	#endif
	// send to client number
	send(sessfd, msg, strlen(msg), 0);
}


// process unlink RPC
// parameter: unlink header
// sessfd: client connection
void process_unlink(unlink_request_header_t* unlink_header, int sessfd) {
	// get parameter
	int filename_len = unlink_header->filename_len;
	char* pathname = malloc(filename_len);
	memcpy(pathname, unlink_header->data, filename_len);
	int unlink_state = unlink(pathname);

	if (unlink_state < 0) {
		unlink_state = -1 * errno;
	} 

	// send back result
	char msg[20];
	sprintf(msg, "%d", unlink_state);
	#ifdef DEBUG
		fprintf(stderr, "Server going to send UNLILNK data! %s \n", msg);
	#endif
	send(sessfd, msg, strlen(msg), 0);
	free(pathname);
}

// process read RPC
// parameter: read header
// sessfd: client connection
void process_read(read_request_header_t * read_header, int sessfd) {
	// get read parameter
	int fd = read_header->fd - FD_OFFSET;
	int nbyte = read_header->nbyte;
	#ifdef DEBUG
		fprintf(stderr, "Server going to do READ fd:%d nbyte:%d \n", fd, nbyte);
	#endif
	char* buf = malloc(nbyte);
	int read_num = read(fd, buf, nbyte);
	char *buffer = NULL;
	int length = 0;

	// marshall data
	if (read_num < 0) {
		read_num = -1 * errno;
		length = sizeof(request_header_t) + sizeof(read_reply_header_t);
		buffer = malloc(length);
		request_header_t *header = (request_header_t *)buffer;
		header->opcode = READ;
		header->total_len = length;
		read_reply_header_t* reply_header = (read_reply_header_t *)header->data;
		reply_header->read_num = read_num;
	} else {
		length = sizeof(request_header_t) + sizeof(read_reply_header_t) + read_num;
		buffer = malloc(length);
		request_header_t *header = (request_header_t *)buffer;
		header->opcode = READ;
		header->total_len = length;
		read_reply_header_t* reply_header = (read_reply_header_t *)header->data;
		reply_header->read_num = read_num;
		memcpy(reply_header->data, buf, read_num);		
	}
	#ifdef DEBUG
		fprintf(stderr, "Server going to send READ_num %d \n", read_num);
	#endif
	free(buf);

	// send back result
	int k = -1;
	while (k <= 0){
		k = send(sessfd, buffer, length, 0);	    // send message; should check return value
	}
	free(buffer);
}


// process dirtree RPC
// parameter: dirtree header
// sessfd: client connection
void process_dirtree(dirtree_request_header_t* dirtree_header, int sessfd) {
	// get parameter
	int file_len = dirtree_header->filename_len;
	char * filename = malloc(file_len);
	memcpy(filename, dirtree_header->data, file_len);
	dirtreenode* dirHead = getdirtree(filename);
	char *buffer = NULL;
	int length = 0;

	// marshal data
	if (dirHead == NULL) {
		int num_subdirs = -1 * errno;
		length = sizeof(request_header_t) + sizeof(dirtree_reply_header_t);
		buffer = malloc(length);
		request_header_t *header = (request_header_t *)buffer;
		header->opcode = DIRTREE;
		header->total_len = length;
		dirtree_reply_header_t* reply_header = (dirtree_reply_header_t *)header->data;
		reply_header->num_subdirs = num_subdirs;		
	} else {
		int total_len = get_total_len(dirHead);
		length = sizeof(request_header_t) + total_len;
		buffer = malloc(length);
		request_header_t *header = (request_header_t *)buffer;
		header->opcode = DIRTREE;
		header->total_len = length;
		dirtree_reply_header_t* reply_header = (dirtree_reply_header_t *)header->data;
		marshalTree(dirHead, (void *)reply_header);
	}

	#ifdef DEBUG
		fprintf(stderr, "Server dirHead send %d bytes \n", length);
	#endif

	// send data
	int k = -1;
	while (k <= 0){
		k = send(sessfd, buffer, length, 0);	    // send message; should check return value
	}
	free(filename);
	free(buffer);
	freedirtree(dirHead);
}


// marshall tree data using BFS, mashall tree into buf
void marshalTree(dirtreenode* dirHead, void* buf) {
	dirtree_reply_header_t * reply_header = (dirtree_reply_header_t *)buf;
	reply_header->num_subdirs = dirHead->num_subdirs;
	int filename_len = strlen(dirHead->name) + 1;
	reply_header->filename_len = filename_len;
	memcpy(reply_header->data, dirHead->name, filename_len);

	buf += (filename_len + sizeof(dirtree_reply_header_t));
	dirtreenode ** queue = malloc(1 * sizeof(dirtreenode*));
	queue[0] = dirHead;
	int i = 0;
	int j = 0;
	int size = 1;
	int current_level = dirHead->num_subdirs;
	dirtreenode ** queue2 = NULL;

	// BFS marshall tree data
	while (size > 0) {
		int index = 0;
		int next_level = 0;
		queue2 = malloc(current_level * sizeof(dirtreenode *));
		for (i = 0; i < size; i ++) {
			int sub_dir_num = queue[i]->num_subdirs;
			for (j = 0; j < sub_dir_num; j ++) {
				dirtreenode* node = queue[i]->subdirs[j];
				dirtree_reply_header_t * header = (dirtree_reply_header_t *)buf;
				header->num_subdirs = node->num_subdirs;
				header->filename_len = strlen(node->name) + 1;
				memcpy(header->data, node->name, header->filename_len);
				buf += (header->filename_len + sizeof(dirtree_reply_header_t));
				queue2[index ++] = node;
				next_level += node->num_subdirs;
			}
		}
		size = index;
		current_level = next_level;

		free(queue);
		queue = queue2;
	}
	free(queue2);
}


// Get total length memory needed to marshall tree data using DFS
int get_total_len(dirtreenode* dirHead) {
	if (dirHead == NULL) return 0;
	int total_len = 0;
	int i = 0;
	total_len += (1 + strlen(dirHead->name) + sizeof(dirtree_reply_header_t));
	for (i = 0; i < dirHead->num_subdirs; i ++) {
		total_len += get_total_len(dirHead->subdirs[i]);
	}
	return total_len;
}


// process write RPC
// parameter: write header
// sessfd: client connection
void process_write(write_request_header_t* write_header, int sessfd) {
	// parse parameter
	int fd = write_header->fd - FD_OFFSET;
	size_t count = write_header->count;
	char* buffer = malloc(count + 1);
	memcpy(buffer, write_header->data, count);
	buffer[count] = 0;

	#ifdef DEBUG
		fprintf(stderr, "Server WRITE data! %d \n", fd);
	#endif	

	// call write
	int write_num = write(fd, buffer, count);
	if (write_num < 0) {
		write_num = -1 * errno;
	} 
	free(buffer);
	char msg[20];
	sprintf(msg, "%d", write_num);
	#ifdef DEBUG
		fprintf(stderr, "Server going to send WRITE RESULT %s \n", msg);
	#endif

	// send to client write number
	send(sessfd, msg, strlen(msg), 0);
}


// process close RPC
// parameter: close header
// sessfd: client connection
void process_close(close_request_header_t * close_header, int sessfd) {
	// parse parameter
	int fd = close_header->fd - FD_OFFSET;
	int state = close(fd);
	#ifdef DEBUG
		fprintf(stderr, "Server going to CLOSE fd %d \n", fd);
	#endif
	if (state < 0) {
		state = -1 * errno;
	}

	// send to client close state
	char msg[20];
	sprintf(msg, "%d", state);
	#ifdef DEBUG
		fprintf(stderr, "Server CLOSE result is %s \n", msg);
	#endif
	send(sessfd, msg, strlen(msg), 0);
}


// process open RPC
// parameter: open header
// sessfd: client connection
void process_open (open_request_header_t * open_header, int sessfd) {
	// parse paramter
	int flags = open_header->flag;
	int file_len = open_header->filename_len;
	char *path = malloc(file_len);
	memcpy(path, open_header->data, file_len);
	#ifdef DEBUG
		fprintf(stderr, "Server going to OPEN PATH %s \n", path);
	#endif

	// open operation
	int open_fd = 0;
	if (flags & O_CREAT) {
		open_fd = open(path, flags, open_header->mode);
	} else {
		open_fd = open(path, flags);
	}

	// error handling and return value
	if (open_fd < 0) {
		open_fd = -1 * errno;
	} else {
		open_fd += FD_OFFSET;  // perform conversion
	}

	// send to client open result
	char msg[20];
	sprintf(msg, "%d", open_fd);
	#ifdef DEBUG
		fprintf(stderr, "Server going to send OPEN data! %s \n", msg);
	#endif
	free(path);
	send(sessfd, msg, strlen(msg), 0);
}

// sigchild handler
void sigchld_handler(int sig) {
	while (waitpid(-1, 0, WNOHANG) > 0)
		;
	return;
}
