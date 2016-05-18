/*
 * 15440 project 1 checkpoint 3, a network file system
 * Author : Yuqi Liu< yuqil@andrew.cmu.edu>
 */
#define _GNU_SOURCE
#include <dlfcn.h>
#include <stdio.h>
#include <stdarg.h>
#include <stdlib.h>
#include <sys/types.h>
#include <sys/stat.h>
#include <sys/socket.h>
#include <fcntl.h>
#include <arpa/inet.h>
#include <netinet/in.h>
#include <string.h>
#include <err.h>
#include <errno.h>
#include <assert.h>
#include "../include/dirtree.h"
#include "RPClib.h"

#define PORT_CHANGE      // use for local test port change
#define DEBUG            // use for debug mode
#define MAXMSGLEN 512    
typedef struct dirtreenode dirtreenode;

// The following line declares a function pointer with the same prototype as the open function.  
int (*orig_open)(const char *pathname, int flags, ...);  // mode_t mode is needed when flags includes O_CREAT
int (*orig_close)(int fd);  
int (*orig_read)(int fildes, void *buf, size_t nbyte); 
int (*orig_write)(int fd, const void *buf, size_t count); 
int (*orig_stat)(int ver, const char * path, struct stat * stat_buf); 
off_t(*orig_lseek)(int fd, off_t offset, int whence); 
int (*orig_unlink)(const char *pathname);
ssize_t (*orig_getdirentries)(int fd, char *buf, size_t nbytes , off_t *basep);
struct dirtreenode*(*orig_getdirtree)( const char *path );
void (*orig_freedirtree)( struct dirtreenode* dt );

// The fuctions for unmarshall dirtree and create new dirtreenode object
struct dirtreenode* process_client_getdirtree(dirtree_reply_header_t * dirtree_header);
dirtreenode * new_dirtreenode (char * name, int sub_num);

// Global variable for server conncetion
char *serverip;
char *serverport;
unsigned short port;
int sockfd = 0;


// This is replacement for the open function from libc.
int open(const char *pathname, int flags, ...) 
{
	int rv = 0;
	mode_t m=0;

	// get mode parameter if needed
	if (flags & O_CREAT) {
		va_list a;
		va_start(a, flags);
		m = va_arg(a, mode_t);
		va_end(a);
	}

	// marshal data
	int length = 1 + strlen(pathname) + sizeof(request_header_t) + sizeof(open_request_header_t);
	char *buffer = malloc(length);
	request_header_t *header = (request_header_t *)buffer;
	header->opcode = OPEN;
	header->total_len = length;
	open_request_header_t* open_header = (open_request_header_t *)header->data;
	open_header->flag = flags;
	open_header->filename_len = strlen(pathname) + 1;
	if (flags & O_CREAT) {
		open_header->mode = m;
	}
	memcpy(open_header->data, pathname, strlen(pathname) + 1);
	#ifdef DEBUG
		fprintf(stderr, "Client OPEN path: %s \n", pathname);
	#endif

	// send data
	int flag = send(sockfd, buffer, length, 0);	
	if (flag < -1) {
		err(1, 0);
		return -1;
	}

	// get message back
	rv = recv(sockfd, buffer, length, 0);	// get message
	if (rv<0) err(1,0);			// in case something went wrong
	buffer[rv]=0;				    // null terminate string to print
	int fd = atoi(buffer);
	free(buffer);
	#ifdef DEBUG
		fprintf(stderr, "Client OPEN file descriptor ! %d \n", fd);
	#endif
	
	// error case handling: set errno and return -1
	if (fd < 0) {
		errno = -1 * fd;
		return -1;
	}
	// return file discriptor
	return fd;
}


// replacement for the close function
int close(int fd) {
	int rv = 0;
	// if it is local fd number call local function
	if (fd < FD_OFFSET) {
		return orig_close(fd);
	}
	
	// marshal data
	int length = sizeof(request_header_t) + sizeof(close_request_header_t);
	char *buffer = malloc(length);
	request_header_t *header = (request_header_t *)buffer;
	header->opcode = CLOSE;
	header->total_len = length;
	close_request_header_t* close_header = (close_request_header_t *)header->data;
	close_header->fd  = fd;

	// send data
	#ifdef DEBUG
		fprintf(stderr, "Client CLOSE file descriptor %d \n", fd);
	#endif
	int flag = send(sockfd, buffer, length, 0);	   
	if (flag < -1) {
		err(1, 0);
		return -1;
	}

	// get message back
	rv = recv(sockfd, buffer, length, 0);	// get message
	if (rv<0) {
		err(1,0);
		return -1;
	}			
	buffer[rv]=0;				    // null terminate string to print
	int state = atoi(buffer);
	free(buffer);
	#ifdef DEBUG
		fprintf(stderr, "Client CLOSE state ! %d \n", state);
	#endif
	
	// error case handling
	if (state < 0) {
		errno = -1 * state;
		return -1;
	}
	return state;
}


// replacement for the read function
int read(int fildes, void *buf, size_t nbyte) {
	// if it is a local fd, call local read
	if (fildes < FD_OFFSET) {
		return orig_read(fildes, buf, nbyte);
	}

	// marshal data
	int length = sizeof(request_header_t) + sizeof(read_request_header_t);
	char *buffer2 = malloc(length);
	request_header_t *header = (request_header_t *)buffer2;
	header->opcode = READ;
	header->total_len = length;
	read_request_header_t* read_header = (read_request_header_t *)header->data;
	read_header->fd = fildes;
	read_header->nbyte = nbyte;

	// send data
	int flag = send(sockfd, buffer2, length, 0);	    // send message; should check return value
	if (flag < -1) {
		err(1, 0);
		return -1;
	}
	free(buffer2);
	#ifdef DEBUG
		fprintf(stderr, "Client READ fd %d \n", fildes);
	#endif

	// get message back
	int index = 0;          // loop count
	int read_size = 0;      // size have been read
	length = 0;        		// total length of data
	void * buffer = NULL;   // data buffer
	int rv = 0;
	char *buf2 = malloc(MAXMSGLEN);  // receive data buffer
	// get RPC call data in a loop
	while ((rv=recv(sockfd, buf2, MAXMSGLEN, 0)) > 0) {
		// first time enter loop, create buffer for the whole request
		if (index == 0) {
			request_header_t* header2 = (request_header_t *) buf2;
			length = header2->total_len;    // get length
			buffer = malloc(length);
			memcpy(buffer, buf2, rv);
			index ++;
			read_size += rv;
			// if received all data, begin process
			if (read_size == length) break;
		} else {
			memcpy(buffer + read_size, buf2, rv);
			read_size += rv;
			// if received all data, begin process
			if (read_size == length) break;
		}
	}
	free(buf2);
	if (rv<0) {
		err(1,0);			// in case something went wrong
		return -1;
	}
	request_header_t* header3 = (request_header_t *) buffer;
	read_reply_header_t * read_reply = (read_reply_header_t * ) header3->data;
	#ifdef DEBUG
		fprintf(stderr, "Client READ receive state  %d \n", read_reply->read_num);
	#endif

	// error handling for return value
	if (read_reply->read_num < 0) {
		errno = -1 * read_reply->read_num;
		free(buffer);
		return -1;
	} else {
		int result = read_reply->read_num;
		memcpy(buf, read_reply->data, result);
		free(buffer);
		return result;
	}
}


// reaplacement for the write function
int write(int fd, const void *buf, size_t count) {
	if (fd < FD_OFFSET) {
		return orig_write(fd, buf, count);
	}

	// marshal data
	int length = count + sizeof(request_header_t) + sizeof(write_request_header_t);
	char *buffer = malloc(length);
	request_header_t *header = (request_header_t *)buffer;
	header->opcode = WRITE;
	header->total_len = length;
	write_request_header_t* write_header = (write_request_header_t *)header->data;
	write_header->fd = fd;
	write_header->count = count;
	memcpy(write_header->data, buf, count);

	// send data
	#ifdef DEBUG
		fprintf(stderr, "Client WRITE fd! %d \n", fd);
	#endif
	int flag = send(sockfd, buffer, length, 0);	    // send message; should check return value
	if (flag < -1) {
		err(1, 0);
		return -1;
	}

	// get message back
	int rv = recv(sockfd, buffer, length, 0);	// get message
	if (rv<0) {
		err(1,0);			// in case something went wrong
		return -1;
	}
	buffer[rv]=0;				    // null terminate string to print
	int write_num = atoi(buffer);
	free(buffer);
	#ifdef DEBUG
		fprintf(stderr, "Client receive write length ! %d \n", write_num);
	#endif

	// error case handling
	if (write_num < 0) {
		errno = -1 * write_num;
		return -1;
	}
	return write_num;
}


// replacement for getdirentries function
ssize_t getdirentries(int fd, char *buf, size_t nbytes , off_t *basep) {
	if (fd < FD_OFFSET) {
		return orig_getdirentries(fd, buf, nbytes, basep);
	}

	// marshal data
	int length = sizeof(request_header_t) + sizeof(getdirentries_request_header_t);
	char *buffer = malloc(length);
	request_header_t *header = (request_header_t *)buffer;
	header->opcode = GETENTRY;
	header->total_len = length;
	getdirentries_request_header_t* getEntry_header = (getdirentries_request_header_t *)header->data;
	getEntry_header->fd = fd;
	getEntry_header->nbyte = nbytes;
	getEntry_header->basep = *(basep);

	// send data
	#ifdef DEBUG
		fprintf(stderr, "Client getdirentries fd %d! \n", fd);
	#endif
	int flag = send(sockfd, buffer, length, 0);	    // send message; should check return value
	if (flag < -1) {
		err(1, 0);
		return -1;
	}
	free(buffer);

	// get message back
	int index = 0;         // loop count
	int read_size = 0;     // size have been read
	length = 0;        		// total length of data
	buffer = NULL;   // data buffer
	int rv = 0;
	char *buf2 = malloc(MAXMSGLEN);
	// get RPC call data
	while ((rv=recv(sockfd, buf2, MAXMSGLEN, 0)) > 0) {
		// first time enter loop, create buffer for the whole request
		if (index == 0) {
			request_header_t* header2 = (request_header_t *) buf2;
			length = header2->total_len;    // get length
			buffer = malloc(length);
			memcpy(buffer, buf2, rv);
			index ++;
			read_size += rv;

			// if received all data, begin process
			if (read_size == length) break;
		} else {
			memcpy(buffer + read_size, buf2, rv);
			read_size += rv;
			// if received all data, begin process
			if (read_size == length) break;
		}
	}
	if (rv<0) {
		err(1,0);			// in case something went wrong
		return -1;
	}
	free(buf2);
	request_header_t* header3 = (request_header_t *) buffer;
	getdirentries_reply_header_t * getEntry_reply = (getdirentries_reply_header_t * ) header3->data;
	
	// error handling and return value
	int state = getEntry_reply->read_num;
	#ifdef DEBUG
		fprintf(stderr, "Client getdirentries received %d! \n", state);
	#endif
	if (state < 0) {
		free(buffer);
		errno = -1 * state;
		return -1;
	}
	* basep = getEntry_reply->basep;
	memcpy(buf, getEntry_reply->data, state);
	free(buffer);
	return state;
}


// replacement for lseek function
off_t lseek(int fd, off_t offset, int whence) {
	if (fd < FD_OFFSET) {
		return orig_lseek(fd, offset, whence);
	}

	// marshal data
	int length = sizeof(request_header_t) + sizeof(lseek_request_header_t);
	char *buffer = malloc(length);
	request_header_t *header = (request_header_t *)buffer;
	header->opcode = LSEEK;
	header->total_len = length;
	lseek_request_header_t* lseek_header = (lseek_request_header_t *)header->data;
	lseek_header->fd = fd;
	lseek_header->offset = offset;
	lseek_header->whence = whence;

	// send data
	#ifdef DEBUG
		fprintf(stderr, "Client LSEEK send fd %d! \n", fd);
	#endif
	int flag = send(sockfd, buffer, length, 0);	    // send message; should check return value
	if (flag < -1) {
		err(1, 0);
		return -1;
	}

	// get message back
	int rv = recv(sockfd, buffer, length, 0);	// get message
	if (rv<0) {
		err(1,0);			// in case something went wrong
		return -1;
	}
	buffer[rv]=0;				    // null terminate string to print
	int lseek_num = atoi(buffer);
	#ifdef DEBUG
		fprintf(stderr, "Client receive LSEEK length ! %d \n", lseek_num);
	#endif
	free(buffer);

	// error case handling
	if (lseek_num < 0) {
		errno = -1 * lseek_num;
		return -1;
	}
	return lseek_num;
}


// replacement for xstat function
int __xstat(int ver, const char * path, struct stat * stat_buf) {
	// connect to server
	int rv = 0;

	// marshal data
	int filename_len = strlen(path) + 1;
	int length =  filename_len + sizeof(request_header_t) + sizeof(stat_request_header_t);
	char *buffer = malloc(length);
	request_header_t *header = (request_header_t *)buffer;
	header->opcode = STAT;
	header->total_len = length;
	stat_request_header_t* stat_header = (stat_request_header_t *)header->data;
	stat_header->ver = ver;
	stat_header->filename_len = filename_len;
	memcpy(stat_header->data, path, filename_len);

	// send data
	#ifdef DEBUG
		fprintf(stderr, "Client is going to send data XSTAT! \n");
	#endif
	int flag = send(sockfd, buffer, length, 0);	    // send message; should check return value
	if (flag < -1) {
		err(1, 0);
		return -1;
	}
	free(buffer);

	// get message back
	void* buffer2 = malloc(MAXMSGLEN);
	rv = recv(sockfd, buffer2, MAXMSGLEN, 0);	// get message
	if (rv<0) {
		err(1,0);			// in case something went wrong
		return -1;
	}
	request_header_t* header2 = (request_header_t *) buffer2;
	stat_reply_header_t * stat_reply = (stat_reply_header_t * ) header2->data;
	int state = stat_reply->state;
	#ifdef DEBUG
		fprintf(stderr, "Client receive stat number ! %d \n", state);
	#endif

	// error case handling
	if (state < 0) {
		free(buffer2);
		errno = -1 * state;
		return -1;
	}
	memcpy(stat_buf, stat_reply->data, sizeof(struct stat));
	free(buffer2);
	return 0;
}


// replacement for unlink function
int unlink(const char *pathname) {
	// connect to server
	int rv = 0;
	
	// marshal data
	int filename_len = strlen(pathname) + 1;
	int length = filename_len + sizeof(request_header_t) + sizeof(unlink_request_header_t);
	char *buffer = malloc(length);
	request_header_t *header = (request_header_t *)buffer;
	header->opcode = UNLINK;
	header->total_len = length;
	unlink_request_header_t* unlink_header = (unlink_request_header_t *)header->data;
	unlink_header->filename_len = filename_len;
	memcpy(unlink_header->data, pathname, filename_len);

	// send data
	#ifdef DEBUG
		fprintf(stderr, "Client UNLINK %s! \n", pathname);
	#endif
	int flag = send(sockfd, buffer, length, 0);	    // send message; should check return value
	if (flag < -1) {
		err(1, 0);
		return -1;
	}

	// get message back
	rv = recv(sockfd, buffer, length, 0);	// get message
	if (rv<0) {
		err(1,0);			// in case something went wrong
		return -1;
	}
	buffer[rv]=0;				    // null terminate string to print
	int unlink_num = atoi(buffer);
	#ifdef DEBUG
		fprintf(stderr, "Client receive unlink result ! %d \n", unlink_num);
	#endif

	// error handling and free memory
	free(buffer);
	if (unlink_num < 0) {
		errno = -1 * unlink_num;
		return -1;
	}
	return unlink_num;
}


// replacement for getdirtree
struct dirtreenode* getdirtree( const char *path ) {
	// marshal data
	int length = 1 + strlen(path) + sizeof(request_header_t) + sizeof(dirtree_request_header_t);
	char *buffer = malloc(length);
	request_header_t *header = (request_header_t *)buffer;
	header->opcode = DIRTREE;
	header->total_len = length;
	dirtree_request_header_t* dirtree_header = (dirtree_request_header_t *)header->data;
	dirtree_header->filename_len = strlen(path) + 1;
	memcpy(dirtree_header->data, path, strlen(path) + 1);
	#ifdef DEBUG
		fprintf(stderr, "Client is going to send data for getdirtree! %s \n", path);
	#endif

	// send data
	int flag = send(sockfd, buffer, length, 0);	    // send message; should check return value
	if (flag < -1) {
		err(1, 0);
		return NULL;
	}
	free(buffer);

	// get message back
	int index = 0;         // loop count
	int read_size = 0;     // size have been read
	length = 0;        // total length of data
	buffer = NULL;   // data buffer
	int rv;
	char buf[MAXMSGLEN+1];
	// get RPC call data
	while ((rv=recv(sockfd, buf, MAXMSGLEN, 0)) > 0) {
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
	if (rv<0) {
		err(1,0);			// in case something went wrong
		return NULL;
	}
	#ifdef DEBUG
		fprintf(stderr, "Client receive getdirentrieslength ! %d \n", read_size);
	#endif

	// reconstruct the tree from reply header
	header = (request_header_t*) buffer;
	dirtreenode* dirHead = process_client_getdirtree((dirtree_reply_header_t *)header->data);
	free(buffer);
	return dirHead;	
}


// reconstruct the treenode from reply header using BFS
struct dirtreenode* process_client_getdirtree(dirtree_reply_header_t * dirtree_header) {
	if (dirtree_header -> num_subdirs < 0) {
		errno = -1 * dirtree_header -> num_subdirs;
		return NULL;
	}

	// create dirtree root
	int second_level = dirtree_header -> num_subdirs;
	char* name = malloc(dirtree_header->filename_len);
	memcpy(name, dirtree_header->data, dirtree_header->filename_len);
	dirtreenode *dirHead = new_dirtreenode(name, second_level);

	// BFS reconsturct the tree
	dirtreenode **queue = malloc(1 * sizeof(dirtreenode *));
	queue[0] = dirHead;
	void* buf = (void *) dirtree_header;
	buf += (dirtree_header -> filename_len + sizeof(dirtree_reply_header_t));
	int size = 1;
	int current_level = second_level;
	int i = 0;
	int j = 0;	 
	dirtreenode ** queue2 = NULL;

	while (size > 0) {
		queue2 = malloc(current_level * sizeof(dirtreenode *));
		int next_level = 0;
		int index = 0;
		for (i = 0; i < size; i ++) {
			int sub_dir_num = queue[i] -> num_subdirs;
			for (j = 0; j < sub_dir_num; j ++) {
				dirtree_reply_header_t *sub_tree = (dirtree_reply_header_t *) buf;		
				char * name_buf = malloc (sub_tree->filename_len);
				memcpy(name_buf, sub_tree->data, sub_tree->filename_len);
				int sub_num = sub_tree->num_subdirs;
				dirtreenode * subnode = new_dirtreenode(name_buf, sub_num);
				queue[i]->subdirs[j] = subnode;
				queue2[index ++] = subnode;
				next_level += sub_num;
				buf += (sub_tree->filename_len + sizeof(dirtree_reply_header_t));
			}
		}
		free(queue);
		queue = queue2;
		size = index;
		current_level = next_level;
	}
	if (queue2 != NULL)
		free(queue2);
	return dirHead;
}


// create new dirtreenode object
dirtreenode * new_dirtreenode (char * name, int sub_num) {
	dirtreenode **subdirs = NULL;
	if (sub_num != 0)
		subdirs = malloc(sub_num * sizeof(dirtreenode *)); 
	dirtreenode * p;
	if((p = malloc(sizeof *p)) != NULL){
    	p->name = name;
    	p->num_subdirs = sub_num;
    	p->subdirs = subdirs;
  	}	
  	return p;
}


// replacement for freedirtree, not an RPC call
void freedirtree( struct dirtreenode* dt ) {
	#ifdef DEBUG
		fprintf(stderr, "free tree %p \n", dt);
	#endif
	orig_freedirtree(dt);
}


// This function is automatically called when program is started
void _init(void) {
	// set function pointer orig_function to point to the original function
	orig_open = dlsym(RTLD_NEXT, "open");
	orig_close = dlsym(RTLD_NEXT, "close");
	orig_write = dlsym(RTLD_NEXT, "write");
	orig_read = dlsym(RTLD_NEXT, "read");
	orig_stat = dlsym(RTLD_NEXT, "__xstat");
	orig_lseek = dlsym(RTLD_NEXT, "lseek");
	orig_unlink = dlsym(RTLD_NEXT, "unlink");
	orig_getdirtree = dlsym(RTLD_NEXT, "getdirtree");
	orig_freedirtree = dlsym(RTLD_NEXT, "freedirtree");
	orig_getdirentries = dlsym(RTLD_NEXT, "getdirentries");

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
		#ifdef PORT_CHANGE
			serverport = "14743";
		#endif
	}
	port = (unsigned short)atoi(serverport);

	// connect to server
	sockfd = connect2server(serverip, port);
	if (sockfd < 0) {
		err(1, 0);
	}
}


// This function is automatically called when program ended
void _fini(void) {
	// close socket
	orig_close(sockfd);
}



