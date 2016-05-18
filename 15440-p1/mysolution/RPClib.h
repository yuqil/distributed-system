/*
 * Function prototype and definition for RPC library
 * 
 * Author : Yuqi Liu< yuqil@andrew.cmu.edu>
 */

#include <sys/types.h>

// define operation code 
#define OPEN 0
#define CLOSE 1 
#define READ 2
#define WRITE 3
#define DIRTREE 4
#define UNLINK 5
#define LSEEK 6
#define STAT 7
#define GETENTRY 8

// define server client fd conversion offset
#define FD_OFFSET 25000

// general request header
typedef struct 
{ 
	int opcode;            // Operation code: OPEN/CLOSE..
	int total_len;         // Dictates how many bytes we need to request from recv()
	unsigned char data[0]; // This points to the next byte after this struct
} request_header_t;


// open request header
typedef struct
{ 
	int flag;              // parameter flag      
	mode_t mode;           // parameter mode 
	int filename_len;      // path length
	unsigned char data[0]; // points to path
} open_request_header_t;


// close request header
typedef struct
{ 
	int fd;                // file descriptor
} close_request_header_t;


// read request header
typedef struct
{ 
	int fd;                // file descriptor
	size_t nbyte;          // buffer size
	unsigned char data[0]; // points to data buffer
} read_request_header_t;


// read reply header
typedef struct
{ 
	int read_num;          // read size
	unsigned char data[0]; // points to data read
} read_reply_header_t;


// write request header
typedef struct
{ 
	int fd;                // file descriptor
	size_t count;          // buffer size
	unsigned char data[0]; // points to data buffer
} write_request_header_t;


// getdirtree request header
typedef struct
{ 
	int filename_len;      // path length
	unsigned char data[0]; // points to data buffer
} dirtree_request_header_t;


// getdirtree reply header
typedef struct
{ 
	int num_subdirs;	   // number of subdirs
	int filename_len;      // file name length
	unsigned char data[0]; // points to data buffer
} dirtree_reply_header_t;


// unlink request header
typedef struct
{ 
	int filename_len;      // path length
	unsigned char data[0]; // points to path
} unlink_request_header_t;


// lseek request header
typedef struct
{   int fd;           
	off_t offset; 
	int whence;
} lseek_request_header_t;


// stat request header
typedef struct
{ 
	int ver;  
	int filename_len;      // path length
	unsigned char data[0]; // points to path
} stat_request_header_t;


// stat request header
typedef struct
{ 
	int state;               // return value
	unsigned char data[0];   // points to the data
} stat_reply_header_t;


// getdirentries request header
typedef struct
{ 
	int fd;
	size_t nbyte;
	off_t basep;             // basep value
} getdirentries_request_header_t;


// getdirentries reply header
typedef struct
{ 
	int read_num;    // read number
	off_t basep;     // basep value
	unsigned char data[0];  
} getdirentries_reply_header_t;


// use for debug: send operation log
// first argument: function name
// second argument: the pointer to original close function(used for network socket)
int sendLog(char *function, int (*orig_close)(int fd));


// connect to server at given ip address and given port
int connect2server(char *serverip, int port);


