' Runs on the Roku SceneGraph render thread. No ECP or LAN probing here.
sub init()
    m.video = m.top.findNode("video")
    m.status = m.top.findNode("status")
    m.video.observeField("state", "onVideoState")
    m.pauseAfterStart = false
end sub

function playContent(item as Object) as Object
    if item = invalid then return invalid
    if item.url = invalid then return invalid
    if Left(item.url, 7) <> "http://" and Left(item.url, 8) <> "https://" then
        m.status.text = "Endereço de vídeo inválido"
        m.status.visible = true
        return invalid
    end if

    stream = "mp4"
    if item.format = "mkv" then stream = "mkv"
    content = CreateObject("roSGNode", "ContentNode")
    content.url = item.url
    content.title = item.title
    content.streamFormat = stream
    if item.position <> invalid
        initial = Val(item.position)
        if initial > 0 then content.playStart = initial
    end if
    m.pauseAfterStart = (item.autoplay = "0")
    m.video.control = "stop"
    m.video.content = content
    m.video.visible = true
    m.status.visible = false
    m.video.setFocus(true)
    m.video.control = "play"
    return invalid
end function

function controlPlayback(data as Object) as Object
    if data = invalid then return invalid
    cmd = data.reianix_command
    if cmd = "play"
        m.video.control = "resume"
    else if cmd = "pause"
        m.video.control = "pause"
    else if cmd = "stop"
        m.video.control = "stop"
        m.video.visible = false
        m.status.text = "Transmissão encerrada"
        m.status.visible = true
    else if cmd = "seek"
        if data.seconds <> invalid
            position = Val(data.seconds)
            if position >= 0 then m.video.seek = position
        end if
    end if
    return invalid
end function

sub onVideoState()
    state = m.video.state
    if state = "playing" and m.pauseAfterStart
        m.pauseAfterStart = false
        m.video.control = "pause"
    else if state = "error"
        m.video.visible = false
        m.status.text = "Não foi possível reproduzir este formato na Roku"
        m.status.visible = true
    else if state = "finished"
        m.video.visible = false
        m.status.text = "Vídeo concluído"
        m.status.visible = true
    end if
end sub
