#include "ad_block_client.h"
#include <fstream>
#include <sstream>
#include <cassert>
#include <iostream>
int main(int argc,char** argv){
  AdBlockClient fixture;
  const char* rules="||ad.example^$script,third-party\n@@||ad.example/allowed.js$script\n||popup.example^$popup\nsite.example##.advert\n";
  fixture.parse(rules,true);
  assert(fixture.matches("https://ad.example/banner.js",FOScript,"site.example"));
  assert(!fixture.matches("https://ad.example/allowed.js",FOScript,"site.example"));
  assert(!fixture.matches("https://ad.example/banner.js",FOImage,"site.example"));
  assert(fixture.matches("https://popup.example/path",FOPopup,"site.example"));
  assert(!fixture.matches("https://popup.example/path",FOScript,"site.example"));
  const char* css=fixture.getElementHidingSelectors("https://site.example");assert(css && std::string(css).find(".advert")!=std::string::npos);
  if(argc>1)for(auto name:{"easylist","mobile","portuguese","easyprivacy","annoyances"}) {
    std::ifstream in(std::string(argv[1])+"/"+name+".txt");std::ostringstream data;data<<in.rdbuf();auto text=data.str();
    AdBlockClient client;client.parse(text.c_str(),true);
    std::cout<<name<<" "<<client.numFilters<<" network, "<<client.numCosmeticFilters<<" cosmetic\n";
    assert(!client.matches("https://static.example/episode-720p.mp4",FOMedia,"site.example"));
    assert(!client.matches("https://cdn.example/master.m3u8",FOMedia,"site.example"));
    if(std::string(name)=="easylist")assert(client.matches("https://pagead2.googlesyndication.com/pagead/js/adsbygoogle.js",FOScript,"site.example"));
  }
  std::cout<<"Native filtering, exceptions, type, popup, cosmetics and media regressions passed\n";
}
