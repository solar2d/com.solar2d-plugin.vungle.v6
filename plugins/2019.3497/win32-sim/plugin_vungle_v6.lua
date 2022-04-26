local Library = require "CoronaLibrary"

-- Create stub library for simulator
local lib = Library:new{ name='plugin.vungle.v6', publisherId='com.solar2d' }

-- Default implementations
local function defaultFunction()
    print( "WARNING: The '" .. lib.name .. "' library is not available on this platform." )
end

lib.init= defaultFunction
lib.isLoaded= defaultFunction
lib.show= defaultFunction
lib.load= defaultFunction
lib.enableLogging= defaultFunction
lib.setHasUserConsent= defaultFunction
lib.getVersionString= defaultFunction

-- Return an instance
return lib
