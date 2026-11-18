sub init()
    m.video = m.top.findNode("video")
    m.status = m.top.findNode("status")
    m.top.observeField("playContent", "onPlayContent")
end sub

sub onPlayContent()
    item = m.top.playContent
    if item = invalid or item.url = invalid then return
    content = CreateObject("roSGNode", "ContentNode")
    content.url = item.url
    content.title = item.title
    content.streamFormat = "mp4"
    m.video.content = content
    m.video.visible = true
    m.status.visible = false
    m.video.control = "play"
end sub
