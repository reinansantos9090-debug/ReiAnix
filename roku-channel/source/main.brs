' App entrypoint: Roku ECP launch/dev passes URL + playback metadata as parameters.
sub Main(args as Dynamic)
    screen = CreateObject("roSGScreen")
    port = CreateObject("roMessagePort")
    screen.setMessagePort(port)
    scene = screen.CreateScene("MainScene")
    screen.show()

    if args <> invalid
        if args.contentId <> invalid
            scene.callFunc("playContent", receiverContent(args))
        end if
    end if

    input = CreateObject("roInput")
    input.setMessagePort(port)
    while true
        msg = wait(0, port)
        if type(msg) = "roInputEvent"
            data = msg.GetInfo()
            if data <> invalid
                if data.reianix_command <> invalid
                    scene.callFunc("controlPlayback", data)
                else if data.contentId <> invalid
                    scene.callFunc("playContent", receiverContent(data))
                end if
            end if
        else if type(msg) = "roSGScreenEvent"
            if msg.isScreenClosed() then exit while
        end if
    end while
    screen.close()
end sub

function receiverContent(params as Object) as Object
    item = {
        url: params.contentId
        title: "ReiAnix"
        format: "mp4"
        position: "0"
        autoplay: "1"
    }
    if params.title <> invalid then item.title = params.title
    if params.format <> invalid then item.format = params.format
    if params.position <> invalid then item.position = params.position
    if params.autoplay <> invalid then item.autoplay = params.autoplay
    return item
end function
