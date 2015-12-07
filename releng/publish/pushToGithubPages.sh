#!/bin/bash
dir=$(pwd)

# do not deploy pull requests or branches other than "master"
if [[ "$TRAVIS_PULL_REQUEST" && "$TRAVIS_PULL_REQUEST" != "false" || "$TRAVIS_BRANCH" && "$TRAVIS_BRANCH" != "master" ]]
then
	exit 0
fi

target="$dir/features/net.enilink.platform.updatesite/target-maven/repository"
deploy_branch=gh-pages
http_user=""
if [ "$GH_TOKEN" ]; then http_user="$GH_TOKEN@"; fi
if [ "$1" ]; then GH_REF="$1"; fi
remote_url="https://$http_user$GH_REF"

# author, date and message for deployment commit
name=$(git log -n 1 --format='%aN')
email=$(git log -n 1 --format='%aE')
author="$name <$email>"
date=$(git log -n 1 --format='%aD')
message="Built from $(git rev-parse --short HEAD)"

# create temp dir and clone deploy repository
tempdir=$(mktemp -d -p .)
if ! git clone "$remote_url" "$tempdir"; then exit; fi

# change to deploy repository
cd "$tempdir"
currentbranch=$(git symbolic-ref --short -q HEAD)
if [ "$branch" != "$currentbranch" ]
then
	if ! git checkout "$deploy_branch" &>/dev/null
	then
		git checkout --orphan "$deploy_branch" &>/dev/null
	        git rm -rf . &>/dev/null
	fi
fi
# copy generated files
cp -R "$target/." .

status=$(git status --porcelain)
# if there are any changes
if [ "$status" != "" ]
then
	git config user.email "$email"
	git config user.name "$name"
	git add --all && git commit --message="$message" --author="$author" --date="$date"
	git push -q origin $deploy_branch:$deploy_branch
fi
