sub Main(args as Dynamic)
    screen = CreateObject("roSGScreen")
    port = CreateObject("roMessagePort")
    screen.setMessagePort(port)
    scene = screen.CreateScene("MainScene")
    screen.show()

    content = invalid
    if args <> invalid and args.contentId <> invalid
        parts = args.contentId.Split("|")
        if parts.Count() > 0 then
            content = { url: parts[0], title: "ReiAnix" }
            if parts.Count() > 1 then content.title = parts[1]
        end if
    end if

    if content <> invalid then scene.callFunc("playContent", content)

    input = CreateObject("roInput")
    input.setMessagePort(port)
    while true
        msg = wait(0, port)
        if type(msg) = "roInputEvent"
            data = msg.GetInfo()
            if data <> invalid and data.contentId <> invalid
                parts = data.contentId.Split("|")
                if parts.Count() > 0
                    item = { url: parts[0], title: "ReiAnix" }
                    if parts.Count() > 1 then item.title = parts[1]
                    scene.callFunc("playContent", item)
                end if
            end if
        end if
    end while
end sub
