# One-off direct RLIMIT_RSS diagnostic in a separate process.
import ctypes,resource
class Limits(ctypes.Structure):_fields_=[('soft',ctypes.c_uint64),('hard',ctypes.c_uint64)]
libc=ctypes.CDLL(None,use_errno=True);limits=Limits(8388608,8388608);rc=libc.setrlimit(resource.RLIMIT_RSS,ctypes.byref(limits));err=ctypes.get_errno();print('setrlimit return:',rc,'errno:',err,'after:',resource.getrlimit(resource.RLIMIT_RSS))
