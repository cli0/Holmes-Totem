# imports for tornado
import tornado
import tornado.web
import tornado.httpserver
import tornado.ioloop

# imports for logging
import traceback
import os
from os import path
import mmap

# get ZipParser
import ZipParser
ZipParser = ZipParser.ZipParser


class ZipError (Exception):
    def __init__ (self, status, error):
        self.status = status
        self.error  = error
    def __str__ (self):
        return str(self.status) + " - " + str(self.error)
    def __repr__ (self):
        return repr(str(self))


class ResultSet (object):
    def __init__(self):
        self.data = {}
    def add(self, key, value):
        if key in self.data:
            if isinstance(self.data[key], list):
                self.data[key].append(value)
            else:
                cpy = self.data[key]
                self.data[key] = []
                self.data[key].append(cpy)
                self.data[key].append(value)
        else:
            self.data[key] = value


class BigFile (object):
    def __init__ (self, filename):
        self.file     = open(filename)
        self.datamap  = mmap.mmap(self.file.fileno(), 0, access=mmap.ACCESS_READ)
        self.size     = self.datamap.size()
        self.offset   = 0
    
    def close (self):
        self.datamap.close()
        self.file.close()
        del(self.file)
        del(self.offset)
        del(self)
    
    # provide base functionality
    def read (self, start, stop):
        if start is None or start < 0:
            start = 0
        if stop is None or stop > self.size:
            stop = self.size
        if start >= self.size:
            start = self.size - 1
        if stop > self.size:
            stop = self.size
        self.datamap.seek(0)
        return self.datamap[(self.offset+start):(self.offset+stop)]
    
    def seek (self, position):
        self.datamap.seek(self.offset+position)
    
    def tell (self):
        return self.datamap.tell()
    
    def find (self, needle):
        self.datamap.seek(0)
        result = self.datamap.find(needle, self.offset)
        if result != -1:
            result -= self.offset
        return result
    
    def startswith (self, needle):
        return self[0:len(needle)] == needle
    
    # extended slicing
    def __getitem__ (self, key):
        if isinstance(key, slice):
            return self.read(key.start, key.stop)
        else:
            return self.read(key.start, key.start+1)
    
    def subfile (self, start):
        class SubFile (BigFile):
            # lightweight (without own payload) subtype of BigFile
            def __init__ (self, file, datamap, start, size):
                self.file     = file
                self.datamap  = datamap
                self.size     = size
                self.offset   = start
            def close (self):
                pass  # remove close ability
            def subfile (self, start):
                pass  # remove subfile ability
            def adjust (self, start):
                self.offset += start  # add offset adjustment ability
        return SubFile(self.file, self.datamap, self.offset+start, self.size)
    
    # provide standard functions
    def __len__ (self):
        return self.size




class ZipMetaProcess(tornado.web.RequestHandler):
    def get(self, filename):
        resultset = ResultSet()
        try:
            # read file
            fullPath = os.path.join('/tmp/', filename)
            data     = BigFile(fullPath)
            
            # exclude non-zip
            if len(data) < 4:
                raise ZipError(400, "Not enough filedata.")
            if data[:4] not in [ZipParser.zipLDMagic, ZipParser.zipCDMagic]:
                raise ZipError(400, "Not a zip file.")
            
            # parse
            parser    = ZipParser(data)
            parsedZip = parser.parseZipFile()
            if not parsedZip:
                raise ZipError(400, "Could not parse file as a zip file")
            
            # clean up
            data.close()
            
            # fetch result
            for centralDirectory in parsedZip:
                zipfilename = centralDirectory["ZipFileName"]
                zipentry = ResultSet()
                
                for name, value in centralDirectory.iteritems():
                    if name == 'ZipExtraField':
                        continue
                    
                    if type(value) is list or type(value) is tuple:
                        for element in value:
                            zipentry.add(name, str(element))
                    
                    # Add way to handle dictionary.
                    #if type(value) is dict: ...
                    else:
                        zipentry.add(name, str(value))
                    
                if centralDirectory["ZipExtraField"]:
                    for dictionary in centralDirectory["ZipExtraField"]:
                        zipextra = ResultSet()
                        if dictionary["Name"] == "UnknownHeader":
                            for name, value in dictionary.iteritems():
                                if name == "Data":
                                    value = "Data"
                                zipextra.add(name, str(value))
                        else:
                            for name, value in dictionary.iteritems():
                                zipextra.add(name, str(value))
                        zipentry.add(dictionary["Name"], zipextra.data)
                else:
                    zipentry.add("ZipExtraField", "None")
                
                resultset.add(zipfilename, zipentry.data)
            
            self.write({"files": resultset.data})
        
        except ZipError as ze:
            self.set_status(ze.status, str(ze.error))
            self.write("")
        except Exception as e:
            self.set_status(500, "Unknown error happened")
            x = str(traceback.format_exc(e)).replace("\n  File","\n\n  File")
            x = x.replace("<","&lt;").replace(">","&gt;")
            self.write("<pre>"+x+"</pre>")
            # self.write({"error": traceback.format_exc(e)})


class Info(tornado.web.RequestHandler):
    # Emits a string which describes the purpose of the analytics
    def get(self):
        description = """
<p>Copyright 2015 Holmes Processing

<p>Description: Gathers meta information about a zip file.

<p>Usage: ip-address:port/zipmeta/sampleID
        """
        self.write(description)


class ZipMetaApp(tornado.web.Application):
    def __init__(self):
        handlers = [
            (r'/', Info),
            (r'/zipmeta/([a-zA-Z0-9\-]*)', ZipMetaProcess),
        ]
        settings = dict(
            template_path=path.join(path.dirname(__file__), 'templates'),
            static_path=path.join(path.dirname(__file__), 'static'),
        )
        tornado.web.Application.__init__(self, handlers, **settings)
        self.engine = None


def main():
    server = tornado.httpserver.HTTPServer(ZipMetaApp())
    server.listen(7715)
    tornado.ioloop.IOLoop.instance().start()


if __name__ == '__main__':
    main()
