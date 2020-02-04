# Information about this repository

This repository has been formed from the original android/Wish repository at ControlThings Oy Ab using the following commands:

```sh
git clone -b v1.0.0-release foremost.controlthings.fi:/ct/mist/android/Wish  --depth 1
echo 3744c5fc894080dd42e0fbff19e7fd1e9a1e34c5 >.git/info/grafts
git filter-branch -- --all
git remote remove origin
#Check that there are not other remotes
git prune
git gc --aggressive
```
